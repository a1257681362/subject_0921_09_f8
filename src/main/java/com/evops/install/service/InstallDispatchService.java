package com.evops.install.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.asset.support.ImageFingerprints;
import com.evops.common.BusinessException;
import com.evops.install.InstallEnums;
import com.evops.install.dto.AssignRequest;
import com.evops.install.dto.DispatchDetailView;
import com.evops.install.dto.RescheduleRequest;
import com.evops.install.entity.InstallDispatch;
import com.evops.install.entity.InstallEvent;
import com.evops.install.entity.InstallNotice;
import com.evops.install.entity.InstallPhoto;
import com.evops.install.entity.InstallTeam;
import com.evops.install.entity.InstallTeamSlot;
import com.evops.install.mapper.InstallDispatchMapper;
import com.evops.install.mapper.InstallEventMapper;
import com.evops.install.mapper.InstallNoticeMapper;
import com.evops.install.mapper.InstallPhotoMapper;
import com.evops.install.mapper.InstallTeamMapper;
import com.evops.install.mapper.InstallTeamSlotMapper;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ledger.support.Operators;
import com.evops.install.support.InstallPhotoStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 安装派单主线：
 * 上刊单确认（开单）同事务自动生成派单 → 按资质+档期自动匹配安装队
 * （高空证过期接不了高空点位；一个队同一时段只排一个点位）→ 师傅到场拍照打卡
 * → 完工回传回执（上刊单跟着走状态）；改期/改派/取消都通知到队。
 *
 * 口径：
 * - 每流转一次 version_no +1 并写只追加事件（带单据快照 JSON）；DONE/CANCELED 关闭即冻结；
 * - 时段占用走 t_install_team_slot（队+日期+时段唯一），完工/取消/改期释放后可再排；
 * - 打卡照片必填（没照片不算打卡）；派单关闭前照片一律不许删，关闭后删一张写一条事件。
 */
@Service
public class InstallDispatchService {

    private final InstallDispatchMapper dispatchMapper;
    private final InstallTeamMapper teamMapper;
    private final InstallTeamSlotMapper slotMapper;
    private final InstallEventMapper eventMapper;
    private final InstallPhotoMapper photoMapper;
    private final InstallNoticeMapper noticeMapper;
    private final PostingOrderMapper postingOrderMapper;
    private final InstallTeamService teamService;
    private final InstallPhotoStorage storage;
    private final ObjectMapper objectMapper;

    public InstallDispatchService(InstallDispatchMapper dispatchMapper, InstallTeamMapper teamMapper,
                                  InstallTeamSlotMapper slotMapper, InstallEventMapper eventMapper,
                                  InstallPhotoMapper photoMapper, InstallNoticeMapper noticeMapper,
                                  PostingOrderMapper postingOrderMapper, InstallTeamService teamService,
                                  InstallPhotoStorage storage, ObjectMapper objectMapper) {
        this.dispatchMapper = dispatchMapper;
        this.teamMapper = teamMapper;
        this.slotMapper = slotMapper;
        this.eventMapper = eventMapper;
        this.photoMapper = photoMapper;
        this.noticeMapper = noticeMapper;
        this.postingOrderMapper = postingOrderMapper;
        this.teamService = teamService;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    // ---- 上刊单联动（网关入口） ----

    /**
     * 上刊单确认（开单）后生成派单并自动匹配：计划施工从发生日起、在上刊周内逐日逐时段
     * 找第一个资质合格且档期空闲的在册队。找不到不抛异常——派单停 PENDING 待派工，开单不受影响。
     */
    @Transactional(rollbackFor = Exception.class)
    public InstallDispatch generateFor(PostingOrder order, Site site) {
        if (!LedgerEnums.KIND_POSTING.equals(order.getOrderKind())) {
            return null;
        }
        InstallDispatch dispatch = new InstallDispatch();
        dispatch.setDispatchNo(generateDispatchNo());
        dispatch.setOrderId(order.getId());
        dispatch.setOrderNo(order.getOrderNo());
        dispatch.setSiteId(site.getId());
        dispatch.setSiteNo(site.getSiteNo());
        dispatch.setHighAltitude(site.getHighAltitude() != null ? site.getHighAltitude() : 0);
        dispatch.setCreativeName(order.getCreativeName());
        dispatch.setStatus(InstallEnums.DISPATCH_PENDING);
        dispatch.setVersionNo(1);
        dispatch.setOperatorName(order.getOperatorName());
        try {
            dispatchMapper.insert(dispatch);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("派单号冲突，请重试");
        }
        // 上刊单跟着走状态：待安装
        updateOrderInstallStatus(order, InstallEnums.ORDER_INSTALL_PENDING);

        // 先试占一个资质合格且档期空闲的时段；找不到不抛异常——派单停 PENDING 待派工，开单不受影响
        Assignment held = tryAutoMatch(dispatch, order);
        appendEvent(dispatch, InstallEnums.EVENT_CREATE, order.getOperatorName(),
                "上刊单确认，自动生成安装派单（点位 " + dispatch.getSiteNo()
                        + (dispatch.getHighAltitude() == 1 ? "，高空点位" : "")
                        + (held == null ? "），未匹配到资质合格且档期空闲的安装队，待派工" : "）"));
        if (held != null) {
            applyAssign(dispatch, held.team, held.date, held.slot, InstallEnums.ASSIGN_AUTO, order.getOperatorName());
            appendEvent(dispatch, InstallEnums.EVENT_ASSIGN, order.getOperatorName(),
                    "自动匹配安装队「" + held.team.getTeamName() + "」，计划 " + held.date + " "
                            + InstallEnums.slotLabel(held.slot) + "（资质 "
                            + (dispatch.getHighAltitude() == 1
                                    ? "高空证有效至 " + held.team.getCertValidUntil() : "普通点位无需高空证") + "）");
            appendNotice(dispatch, held.team, InstallEnums.NOTICE_ASSIGN,
                    order.getOperatorName(), assignContent(dispatch));
        }
        return dispatch;
    }

    /** 上刊单落架/对冲：取消在途派单（释放时段、通知到队）；已关闭的派单不动。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelFor(PostingOrder order, String operator, String reason) {
        InstallDispatch dispatch = dispatchMapper.selectOne(new LambdaQueryWrapper<InstallDispatch>()
                .eq(InstallDispatch::getOrderId, order.getId())
                .orderByDesc(InstallDispatch::getId).last("LIMIT 1"));
        if (dispatch == null || isClosed(dispatch)) {
            return;
        }
        cancelDispatch(dispatch, operator, reason);
        updateOrderInstallStatus(order, InstallEnums.ORDER_INSTALL_CANCELED);
    }

    // ---- 手工派工 / 改派 ----

    /**
     * 手工派工（PENDING）或改派（ASSIGNED 换队/换时段）：与自动匹配同一套校验——
     * 队必须在册、高空点位必须证有效、同队同时段只排一个点位。改派时新旧队都通知。
     */
    @Transactional(rollbackFor = Exception.class)
    public InstallDispatch assign(String dispatchNo, AssignRequest request) {
        InstallDispatch dispatch = requireByNo(dispatchNo);
        requireStatusIn(dispatch, "派工", InstallEnums.DISPATCH_PENDING, InstallEnums.DISPATCH_ASSIGNED);
        String operator = Operators.resolve(request.getOperatorName());
        String slot = normalizeSlot(request.getTimeSlot());
        InstallTeam team = teamService.requireByNo(request.getTeamNo());
        requireTeamAssignable(team, dispatch, request.getPlannedDate());

        if (InstallEnums.DISPATCH_ASSIGNED.equals(dispatch.getStatus())
                && team.getId().equals(dispatch.getTeamId())
                && request.getPlannedDate().equals(dispatch.getPlannedDate())
                && slot.equals(dispatch.getTimeSlot())) {
            throw new BusinessException("与当前派工完全一致（队、日期、时段都没变），无需改派");
        }

        InstallTeam oldTeam = dispatch.getTeamId() != null ? teamById(dispatch.getTeamId()) : null;
        releaseSlot(dispatch);
        occupySlot(team, request.getPlannedDate(), slot, dispatch);

        boolean teamChanged = oldTeam != null && !oldTeam.getId().equals(team.getId());
        applyAssign(dispatch, team, request.getPlannedDate(), slot, InstallEnums.ASSIGN_MANUAL, operator);
        appendEvent(dispatch, InstallEnums.EVENT_ASSIGN, operator,
                (oldTeam == null ? "手工派工给「" + team.getTeamName() + "」"
                        : "改派：「" + oldTeam.getTeamName() + "」→「" + team.getTeamName() + "」")
                        + "，计划 " + dispatch.getPlannedDate() + " " + InstallEnums.slotLabel(slot)
                        + (request.getRemark() != null ? "；" + request.getRemark().trim() : ""));
        if (teamChanged) {
            appendNotice(dispatch, oldTeam, InstallEnums.NOTICE_CANCEL, operator,
                    "派单 " + dispatch.getDispatchNo() + "（点位 " + dispatch.getSiteNo() + "）已改派他队，原安排取消");
        }
        appendNotice(dispatch, team, InstallEnums.NOTICE_ASSIGN, operator, assignContent(dispatch));
        return dispatch;
    }

    // ---- 改期 ----

    /** 改期：仅已派工可改；原因必填，重校档期，释放旧时段占新时段，通知到队。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallDispatch reschedule(String dispatchNo, RescheduleRequest request) {
        InstallDispatch dispatch = requireByNo(dispatchNo);
        requireStatusIn(dispatch, "改期", InstallEnums.DISPATCH_ASSIGNED);
        if (request.getReason() == null || request.getReason().trim().isEmpty()) {
            throw new BusinessException("改期原因不能为空，队里要知道为什么改");
        }
        String operator = Operators.resolve(request.getOperatorName());
        String slot = normalizeSlot(request.getTimeSlot());
        InstallTeam team = teamById(dispatch.getTeamId());
        if (request.getPlannedDate().equals(dispatch.getPlannedDate()) && slot.equals(dispatch.getTimeSlot())) {
            throw new BusinessException("新计划与当前一致（日期、时段都没变），无需改期");
        }
        // 高空点位：改期后的施工日同样要证有效（证可能在新旧日期之间过期）
        requireQualifiedIfHighAltitude(dispatch, team, request.getPlannedDate());

        LocalDate oldDate = dispatch.getPlannedDate();
        String oldSlot = dispatch.getTimeSlot();
        releaseSlot(dispatch);
        occupySlot(team, request.getPlannedDate(), slot, dispatch);

        dispatch.setPlannedDate(request.getPlannedDate());
        dispatch.setTimeSlot(slot);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        dispatchMapper.updateById(dispatch);
        appendEvent(dispatch, InstallEnums.EVENT_RESCHEDULE, operator,
                "改期：" + oldDate + " " + InstallEnums.slotLabel(oldSlot) + " → "
                        + request.getPlannedDate() + " " + InstallEnums.slotLabel(slot)
                        + "，原因：" + request.getReason().trim());
        appendNotice(dispatch, team, InstallEnums.NOTICE_RESCHEDULE, operator,
                "派单 " + dispatch.getDispatchNo() + "（点位 " + dispatch.getSiteNo() + "）改期：原 "
                        + oldDate + " " + InstallEnums.slotLabel(oldSlot) + " → 新 "
                        + request.getPlannedDate() + " " + InstallEnums.slotLabel(slot)
                        + "，原因：" + request.getReason().trim());
        return dispatch;
    }

    // ---- 到场打卡 ----

    /** 师傅到场拍照打卡：照片必填（没照片不算打卡）；上刊单转「安装中」。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallDispatch checkin(String dispatchNo, MultipartFile[] photos, String operatorName) {
        InstallDispatch dispatch = requireByNo(dispatchNo);
        requireStatusIn(dispatch, "到场打卡", InstallEnums.DISPATCH_ASSIGNED);
        if (photos == null || Arrays.stream(photos).allMatch(f -> f == null || f.isEmpty())) {
            throw new BusinessException("到场打卡必须传照片，没照片不算打卡");
        }
        String operator = Operators.resolve(operatorName);
        LocalDateTime now = LocalDateTime.now();

        dispatch.setCheckinOperator(operator);
        dispatch.setCheckinTime(now);
        dispatch.setStatus(InstallEnums.DISPATCH_ARRIVED);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        dispatchMapper.updateById(dispatch);

        savePhotos(dispatch, InstallEnums.PHASE_CHECKIN, photos, operator);
        appendEvent(dispatch, InstallEnums.EVENT_CHECKIN, operator,
                "师傅到场拍照打卡（队「" + dispatch.getTeamName() + "」，点位 " + dispatch.getSiteNo() + "）");
        updateOrderInstallStatus(dispatch.getOrderId(), InstallEnums.ORDER_INSTALL_INSTALLING);
        return dispatch;
    }

    // ---- 完工回执 ----

    /** 完工回传回执：回执说明必填、完工照片可选；释放时段，上刊单转「已完工」，关闭即冻结。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallDispatch done(String dispatchNo, String receiptContent, MultipartFile[] photos, String operatorName) {
        InstallDispatch dispatch = requireByNo(dispatchNo);
        requireStatusIn(dispatch, "完工回执", InstallEnums.DISPATCH_ARRIVED);
        if (receiptContent == null || receiptContent.trim().isEmpty()) {
            throw new BusinessException("完工回执必须填写回执说明（干了什么、画面是否上齐）");
        }
        String operator = Operators.resolve(operatorName);
        LocalDateTime now = LocalDateTime.now();

        dispatch.setReceiptContent(trimTo(receiptContent, 1000));
        dispatch.setDoneOperator(operator);
        dispatch.setDoneTime(now);
        dispatch.setStatus(InstallEnums.DISPATCH_DONE);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        dispatchMapper.updateById(dispatch);
        releaseSlot(dispatch);

        savePhotos(dispatch, InstallEnums.PHASE_DONE, photos, operator);
        appendEvent(dispatch, InstallEnums.EVENT_DONE, operator,
                "完工回传回执（计划 " + dispatch.getPlannedDate() + " " + InstallEnums.slotLabel(dispatch.getTimeSlot())
                        + (now.toLocalDate().isAfter(dispatch.getPlannedDate()) ? "，超期完工" : "，按期完工") + "）");
        updateOrderInstallStatus(dispatch.getOrderId(), InstallEnums.ORDER_INSTALL_INSTALLED);
        return dispatch;
    }

    // ---- 留证照片 ----

    /**
     * 删照片：派单关闭前一律不许删（留证要完整）；关闭后允许删，但删一条写一条事件留底。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePhoto(Long photoId, String operatorName) {
        InstallPhoto photo = requirePhoto(photoId);
        InstallDispatch dispatch = requireById(photo.getDispatchId());
        if (!isClosed(dispatch)) {
            throw new BusinessException("派单 " + dispatch.getDispatchNo()
                    + " 尚未关闭，现场照片不许删除（关闭前一律冻结）");
        }
        String operator = Operators.resolve(operatorName);
        storage.delete(photo.getStoragePath());
        photoMapper.deleteById(photoId);
        appendEvent(dispatch, InstallEnums.EVENT_PHOTO_DELETE, operator,
                "删除现场照片：" + photo.getPhase() + " / " + photo.getFileName() + "（派单已关闭）");
    }

    public byte[] readPhotoBytes(InstallPhoto photo) {
        return storage.read(photo.getStoragePath());
    }

    public InstallPhoto requirePhoto(Long photoId) {
        InstallPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException("现场照片不存在: id=" + photoId);
        }
        return photo;
    }

    // ---- 翻查 ----

    /** 翻查：状态 / 队号 / 点位编号 / 关键字（派单号或上刊单号）过滤。 */
    public List<InstallDispatch> list(String status, String teamNo, String siteNo, String keyword) {
        LambdaQueryWrapper<InstallDispatch> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(InstallDispatch::getStatus, status.trim().toUpperCase());
        }
        if (teamNo != null && !teamNo.trim().isEmpty()) {
            wrapper.eq(InstallDispatch::getTeamNo, teamNo.trim());
        }
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            wrapper.eq(InstallDispatch::getSiteNo, siteNo.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(InstallDispatch::getDispatchNo, kw)
                    .or().like(InstallDispatch::getOrderNo, kw));
        }
        return dispatchMapper.selectList(wrapper.orderByDesc(InstallDispatch::getId));
    }

    /** 上刊单查派单：业务方不用再打电话问调度，单号一翻就知道安装走到哪步。 */
    public InstallDispatch findByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw new BusinessException("上刊单号不能为空");
        }
        return dispatchMapper.selectOne(new LambdaQueryWrapper<InstallDispatch>()
                .eq(InstallDispatch::getOrderNo, orderNo.trim())
                .orderByDesc(InstallDispatch::getId).last("LIMIT 1"));
    }

    public InstallDispatch requireByNo(String dispatchNo) {
        if (dispatchNo == null || dispatchNo.trim().isEmpty()) {
            throw new BusinessException("派单号不能为空");
        }
        InstallDispatch dispatch = dispatchMapper.selectOne(new LambdaQueryWrapper<InstallDispatch>()
                .eq(InstallDispatch::getDispatchNo, dispatchNo.trim()));
        if (dispatch == null) {
            throw new BusinessException("派单不存在: " + dispatchNo);
        }
        return dispatch;
    }

    public InstallDispatch requireById(Long id) {
        InstallDispatch dispatch = dispatchMapper.selectById(id);
        if (dispatch == null) {
            throw new BusinessException("派单不存在: id=" + id);
        }
        return dispatch;
    }

    /** 全量档案：单 + 事件版本 + 照片 + 到队通知记录。 */
    public DispatchDetailView detail(String dispatchNo) {
        InstallDispatch dispatch = requireByNo(dispatchNo);
        DispatchDetailView view = new DispatchDetailView();
        view.setDispatch(dispatch);
        view.setEvents(eventMapper.selectList(new LambdaQueryWrapper<InstallEvent>()
                .eq(InstallEvent::getDispatchId, dispatch.getId())
                .orderByAsc(InstallEvent::getId)));
        view.setPhotos(photoMapper.selectList(new LambdaQueryWrapper<InstallPhoto>()
                .eq(InstallPhoto::getDispatchId, dispatch.getId())
                .orderByAsc(InstallPhoto::getId)));
        view.setNotices(noticeMapper.selectList(new LambdaQueryWrapper<InstallNotice>()
                .eq(InstallNotice::getDispatchId, dispatch.getId())
                .orderByAsc(InstallNotice::getId)));
        return view;
    }

    // ---- 内部方法 ----

    /**
     * 自动匹配：从发生日起在上刊周内逐日逐时段（上午→下午），找第一个资质合格且档期空闲的在册队。
     * 高空点位只匹高空证在计划施工日仍有效的队。找到即占用时段并返回匹配结果，找不到返回 null。
     */
    private Assignment tryAutoMatch(InstallDispatch dispatch, PostingOrder order) {
        List<InstallTeam> teams = teamMapper.selectList(new LambdaQueryWrapper<InstallTeam>()
                .eq(InstallTeam::getStatus, InstallEnums.TEAM_ACTIVE)
                .orderByAsc(InstallTeam::getTeamNo));
        LocalDate date = order.getOccurDate();
        LocalDate end = order.getWeekEnd();
        for (; !date.isAfter(end); date = date.plusDays(1)) {
            for (String slot : Arrays.asList(InstallEnums.SLOT_AM, InstallEnums.SLOT_PM)) {
                for (InstallTeam team : teams) {
                    if (dispatch.getHighAltitude() == 1
                            && !InstallEnums.QUAL_VALID.equals(teamService.qualStatusOf(team, date))) {
                        continue;
                    }
                    if (tryOccupySlot(team, date, slot, dispatch)) {
                        return new Assignment(team, date, slot);
                    }
                }
            }
        }
        return null;
    }

    /** 一次自动匹配的结果（时段已试占成功）。 */
    private static final class Assignment {
        private final InstallTeam team;
        private final LocalDate date;
        private final String slot;

        private Assignment(InstallTeam team, LocalDate date, String slot) {
            this.team = team;
            this.date = date;
            this.slot = slot;
        }
    }

    /** 派工落单：队快照、计划日期时段、匹配资质版本与证件快照、状态转 ASSIGNED。 */
    private void applyAssign(InstallDispatch dispatch, InstallTeam team, LocalDate plannedDate,
                             String slot, String source, String operator) {
        dispatch.setTeamId(team.getId());
        dispatch.setTeamNo(team.getTeamNo());
        dispatch.setTeamName(team.getTeamName());
        dispatch.setPlannedDate(plannedDate);
        dispatch.setTimeSlot(slot);
        dispatch.setMatchedQualVersionNo(team.getCurrentQualVersionNo());
        dispatch.setMatchedCertNo(team.getCertNo());
        dispatch.setMatchedCertValidUntil(team.getCertValidUntil());
        dispatch.setAssignOperator(operator);
        dispatch.setAssignTime(LocalDateTime.now());
        dispatch.setAssignSource(source);
        dispatch.setStatus(InstallEnums.DISPATCH_ASSIGNED);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        dispatchMapper.updateById(dispatch);
    }

    /** 占用队时段：同队同时段只排一个点位；RELEASED 行条件更新抢回，唯一约束兜底并发。 */
    private void occupySlot(InstallTeam team, LocalDate date, String slot, InstallDispatch dispatch) {
        if (!tryOccupySlot(team, date, slot, dispatch)) {
            throw new BusinessException("安装队「" + team.getTeamName() + "」" + date + " "
                    + InstallEnums.slotLabel(slot) + " 已排了别的点位，一个队同一时段只排一个点位");
        }
    }

    private boolean tryOccupySlot(InstallTeam team, LocalDate date, String slot, InstallDispatch dispatch) {
        InstallTeamSlot existing = slotMapper.selectOne(new LambdaQueryWrapper<InstallTeamSlot>()
                .eq(InstallTeamSlot::getTeamId, team.getId())
                .eq(InstallTeamSlot::getPlannedDate, date)
                .eq(InstallTeamSlot::getTimeSlot, slot));
        if (existing == null) {
            InstallTeamSlot row = new InstallTeamSlot();
            row.setTeamId(team.getId());
            row.setPlannedDate(date);
            row.setTimeSlot(slot);
            row.setStatus(InstallEnums.TEAM_SLOT_ACTIVE);
            row.setDispatchId(dispatch.getId());
            row.setDispatchNo(dispatch.getDispatchNo());
            try {
                slotMapper.insert(row);
                return true;
            } catch (DuplicateKeyException e) {
                return false;
            }
        }
        if (InstallEnums.TEAM_SLOT_ACTIVE.equals(existing.getStatus())) {
            return false;
        }
        int updated = slotMapper.update(null, new LambdaUpdateWrapper<InstallTeamSlot>()
                .eq(InstallTeamSlot::getId, existing.getId())
                .eq(InstallTeamSlot::getStatus, InstallEnums.TEAM_SLOT_RELEASED)
                .set(InstallTeamSlot::getStatus, InstallEnums.TEAM_SLOT_ACTIVE)
                .set(InstallTeamSlot::getDispatchId, dispatch.getId())
                .set(InstallTeamSlot::getDispatchNo, dispatch.getDispatchNo()));
        return updated == 1;
    }

    /** 释放队时段：完工/取消/改期/改派后，该时段可再排别的点位。 */
    private void releaseSlot(InstallDispatch dispatch) {
        if (dispatch.getTeamId() == null || dispatch.getPlannedDate() == null || dispatch.getTimeSlot() == null) {
            return;
        }
        slotMapper.update(null, new LambdaUpdateWrapper<InstallTeamSlot>()
                .eq(InstallTeamSlot::getTeamId, dispatch.getTeamId())
                .eq(InstallTeamSlot::getPlannedDate, dispatch.getPlannedDate())
                .eq(InstallTeamSlot::getTimeSlot, dispatch.getTimeSlot())
                .eq(InstallTeamSlot::getDispatchId, dispatch.getId())
                .eq(InstallTeamSlot::getStatus, InstallEnums.TEAM_SLOT_ACTIVE)
                .set(InstallTeamSlot::getStatus, InstallEnums.TEAM_SLOT_RELEASED));
    }

    private void cancelDispatch(InstallDispatch dispatch, String operator, String reason) {
        releaseSlot(dispatch);
        dispatch.setStatus(InstallEnums.DISPATCH_CANCELED);
        dispatch.setCancelReason(trimTo(reason, 255));
        dispatch.setCanceledTime(LocalDateTime.now());
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        dispatchMapper.updateById(dispatch);
        appendEvent(dispatch, InstallEnums.EVENT_CANCEL, operator, "派单取消：" + reason);
        if (dispatch.getTeamId() != null) {
            InstallTeam team = teamById(dispatch.getTeamId());
            if (team != null) {
                appendNotice(dispatch, team, InstallEnums.NOTICE_CANCEL, operator,
                        "派单 " + dispatch.getDispatchNo() + "（点位 " + dispatch.getSiteNo() + "）已取消：" + reason);
            }
        }
    }

    /** 派工前校验：队在册；高空点位证必须在计划施工日有效（过期硬拦，没有旁路）。 */
    private void requireTeamAssignable(InstallTeam team, InstallDispatch dispatch, LocalDate plannedDate) {
        if (!InstallEnums.TEAM_ACTIVE.equals(team.getStatus())) {
            throw new BusinessException("安装队「" + team.getTeamName() + "」已停用，不能派工");
        }
        requireQualifiedIfHighAltitude(dispatch, team, plannedDate);
    }

    private void requireQualifiedIfHighAltitude(InstallDispatch dispatch, InstallTeam team, LocalDate plannedDate) {
        if (dispatch.getHighAltitude() != null && dispatch.getHighAltitude() == 1) {
            teamService.requireQualifiedForHighAltitude(team, plannedDate);
        }
    }

    private InstallTeam teamById(Long teamId) {
        return teamId == null ? null : teamMapper.selectById(teamId);
    }

    private String assignContent(InstallDispatch dispatch) {
        return "派单 " + dispatch.getDispatchNo() + "：点位 " + dispatch.getSiteNo()
                + (dispatch.getHighAltitude() == 1 ? "（高空点位）" : "")
                + "，计划 " + dispatch.getPlannedDate() + " " + InstallEnums.slotLabel(dispatch.getTimeSlot())
                + " 施工，画面「" + dispatch.getCreativeName() + "」";
    }

    private void updateOrderInstallStatus(Long orderId, String installStatus) {
        PostingOrder order = postingOrderMapper.selectById(orderId);
        if (order == null) {
            return;
        }
        updateOrderInstallStatus(order, installStatus);
    }

    private void updateOrderInstallStatus(PostingOrder order, String installStatus) {
        order.setInstallStatus(installStatus);
        postingOrderMapper.updateById(order);
    }

    private List<InstallPhoto> savePhotos(InstallDispatch dispatch, String phase, MultipartFile[] photos, String uploader) {
        List<InstallPhoto> saved = new ArrayList<>();
        if (photos == null) {
            return saved;
        }
        LocalDateTime batchTime = LocalDateTime.now();
        for (MultipartFile file : photos) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            byte[] data;
            try {
                data = file.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("读取上传照片失败: " + file.getOriginalFilename(), e);
            }
            String fingerprint = ImageFingerprints.sha256(data);
            String path = storage.store(dispatch.getDispatchNo(), phase, fingerprint,
                    file.getOriginalFilename(), batchTime, data);
            InstallPhoto photo = new InstallPhoto();
            photo.setDispatchId(dispatch.getId());
            photo.setPhase(phase);
            photo.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "photo.jpg");
            photo.setContentType(file.getContentType());
            photo.setFileSize((long) data.length);
            photo.setStoragePath(path);
            photo.setFingerprintSha256(fingerprint);
            photo.setUploaderName(uploader);
            photo.setUploadTime(batchTime);
            photoMapper.insert(photo);
            saved.add(photo);
        }
        return saved;
    }

    private void appendEvent(InstallDispatch dispatch, String eventType, String operator, String detail) {
        InstallEvent event = new InstallEvent();
        event.setDispatchId(dispatch.getId());
        event.setDispatchNo(dispatch.getDispatchNo());
        event.setEventType(eventType);
        event.setVersionNo(dispatch.getVersionNo());
        event.setOperatorName(operator);
        event.setDetail(detail);
        event.setSnapshot(snapshot(dispatch));
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private void appendNotice(InstallDispatch dispatch, InstallTeam team, String type, String operator, String content) {
        InstallNotice notice = new InstallNotice();
        notice.setDispatchId(dispatch.getId());
        notice.setDispatchNo(dispatch.getDispatchNo());
        notice.setTeamId(team.getId());
        notice.setTeamNo(team.getTeamNo());
        notice.setNoticeType(type);
        notice.setContent(trimTo(content, 500));
        notice.setOperatorName(operator);
        notice.setNotifyTime(LocalDateTime.now());
        noticeMapper.insert(notice);
    }

    private String snapshot(InstallDispatch dispatch) {
        try {
            return objectMapper.writeValueAsString(dispatch);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("派单快照序列化失败: " + dispatch.getDispatchNo(), e);
        }
    }

    private String generateDispatchNo() {
        return String.format("DP%010d", dispatchMapper.nextDispatchSeq());
    }

    private boolean isClosed(InstallDispatch dispatch) {
        return InstallEnums.DISPATCH_DONE.equals(dispatch.getStatus())
                || InstallEnums.DISPATCH_CANCELED.equals(dispatch.getStatus());
    }

    private void requireStatusIn(InstallDispatch dispatch, String action, String... expected) {
        if (isClosed(dispatch)) {
            throw new BusinessException("派单 " + dispatch.getDispatchNo() + " 已关闭（"
                    + InstallEnums.statusLabel(dispatch.getStatus()) + "），不能再" + action);
        }
        for (String s : expected) {
            if (s.equals(dispatch.getStatus())) {
                return;
            }
        }
        throw new BusinessException("派单 " + dispatch.getDispatchNo() + " 当前状态是「"
                + InstallEnums.statusLabel(dispatch.getStatus()) + "」，不能" + action);
    }

    private String normalizeSlot(String raw) {
        if (raw == null) {
            throw new BusinessException("时段不能为空（AM 上午 / PM 下午）");
        }
        String slot = raw.trim().toUpperCase();
        if (!InstallEnums.SLOT_AM.equals(slot) && !InstallEnums.SLOT_PM.equals(slot)) {
            throw new BusinessException("不支持的时段: " + raw + "（AM 上午 / PM 下午）");
        }
        return slot;
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
