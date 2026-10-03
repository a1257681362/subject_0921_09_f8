package com.evops.install;

import com.evops.common.BusinessException;
import com.evops.install.dto.AssignRequest;
import com.evops.install.dto.DispatchDetailView;
import com.evops.install.dto.QualRenewRequest;
import com.evops.install.dto.RescheduleRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.dto.TeamReportRow;
import com.evops.install.entity.InstallDispatch;
import com.evops.install.entity.InstallQualVersion;
import com.evops.install.entity.InstallTeam;
import com.evops.install.service.InstallDispatchService;
import com.evops.install.service.InstallReportService;
import com.evops.install.service.InstallTeamService;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SiteService;
import com.evops.ledger.support.WeekRanges;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F8 安装派单与资质校验端到端（真实 H2 + 真实事务，无 Shiro，经手人显式传）。
 * 覆盖：开单自动生成派单并自动匹配、高空证过期接不了高空点位（自动跳过+手工硬拦）、
 * 同队同时段只排一个点位、到场拍照打卡（照片必填）、完工回执（上刊单跟着走状态）、
 * 改期通知到队、落架/对冲取消派单、资质换证版本链、月度完工量与准时率。
 *
 * 说明：安装队是跨用例共享状态，每个用例先 keepOnly 把非本例队伍停用，保证自动匹配确定。
 */
@SpringBootTest
class InstallDispatchFlowTest {

    @Autowired private InstallDispatchService dispatchService;
    @Autowired private InstallTeamService teamService;
    @Autowired private InstallReportService reportService;
    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    // ----  fixture  ----

    private Site registerSite(boolean highAltitude) {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("INST-SITE"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("安装测试商圈");
        sr.setHighAltitude(highAltitude ? 1 : 0);
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    private InstallTeam registerTeam(String certNo, LocalDate certValidUntil) {
        TeamCreateRequest req = new TeamCreateRequest();
        req.setTeamName(uniqueNo("安装队"));
        req.setContactName("王师傅");
        req.setContactPhone("13800000000");
        req.setCertNo(certNo);
        req.setCertValidUntil(certValidUntil);
        req.setOperatorName("tester");
        return teamService.register(req);
    }

    /** 把除给定队伍外的在册队全部停用，保证本用例自动匹配只落在自己的队上。 */
    private void keepOnly(InstallTeam... mine) {
        Set<String> keep = Arrays.stream(mine).map(InstallTeam::getTeamNo).collect(Collectors.toCollection(HashSet::new));
        for (InstallTeam t : teamService.list(null, InstallEnums.TEAM_ACTIVE)) {
            if (!keep.contains(t.getTeamNo())) {
                teamService.changeStatus(t.getTeamNo(), InstallEnums.TEAM_PAUSED, "tester");
            }
        }
    }

    private PostingOrder createOrder(Site site, LocalDate occurDate) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName("画面-" + site.getSiteNo());
        req.setOccurDate(occurDate);
        req.setOperatorName("tester");
        return orderService.createPosting(req);
    }

    private InstallDispatch dispatchOf(PostingOrder order) {
        return dispatchService.findByOrderNo(order.getOrderNo());
    }

    private MockMultipartFile[] photos(String name) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return new MockMultipartFile[]{
                new MockMultipartFile("photos", name, "image/png", bos.toByteArray())
        };
    }

    private String installStatusOf(PostingOrder order) {
        return orderService.requireByNo(order.getOrderNo()).getInstallStatus();
    }

    // ---- 自动生成与匹配 ----

    @Test
    void postingCreated_generatesDispatchAndAutoMatches() {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        LocalDate today = LocalDate.now();
        PostingOrder order = createOrder(registerSite(false), today);

        InstallDispatch d = dispatchOf(order);
        assertNotNull(d);
        assertTrue(d.getDispatchNo().startsWith("DP"));
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, d.getStatus());
        assertEquals(InstallEnums.ASSIGN_AUTO, d.getAssignSource());
        assertEquals(team.getTeamNo(), d.getTeamNo());
        assertEquals(team.getTeamName(), d.getTeamName());
        assertEquals(today, d.getPlannedDate());
        assertEquals(InstallEnums.SLOT_AM, d.getTimeSlot());
        assertEquals(Integer.valueOf(1), d.getMatchedQualVersionNo());
        assertEquals(0, d.getHighAltitude());
        // 上刊单跟着走状态：待安装
        assertEquals(InstallEnums.ORDER_INSTALL_PENDING, installStatusOf(order));

        DispatchDetailView detail = dispatchService.detail(d.getDispatchNo());
        List<String> events = detail.getEvents().stream()
                .map(com.evops.install.entity.InstallEvent::getEventType).collect(Collectors.toList());
        assertEquals(Arrays.asList(InstallEnums.EVENT_CREATE, InstallEnums.EVENT_ASSIGN), events);
        assertEquals(1, detail.getNotices().size());
        assertEquals(InstallEnums.NOTICE_ASSIGN, detail.getNotices().get(0).getNoticeType());
        assertEquals(team.getTeamNo(), detail.getNotices().get(0).getTeamNo());
    }

    @Test
    void highAltitudeSite_matchesOnlyCertValidTeam() {
        InstallTeam noCert = registerTeam(null, null);
        InstallTeam expired = registerTeam("CERT-OLD", LocalDate.now().minusDays(1));
        InstallTeam valid = registerTeam("CERT-OK", LocalDate.now().plusDays(30));
        keepOnly(noCert, expired, valid);
        PostingOrder order = createOrder(registerSite(true), LocalDate.now());

        InstallDispatch d = dispatchOf(order);
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, d.getStatus());
        // 无证队、高空证已过期队都接不了高空点位，只有证有效的队被匹配
        assertEquals(valid.getTeamNo(), d.getTeamNo());
        assertEquals(1, d.getHighAltitude());
        assertEquals("CERT-OK", d.getMatchedCertNo());
        assertEquals(LocalDate.now().plusDays(30), d.getMatchedCertValidUntil());
    }

    @Test
    void certValidOnPlannedDate_boundaryMatches() {
        LocalDate today = LocalDate.now();
        // 有效期至 = 计划施工日当天：仍有效（有效期至 ≥ 计划施工日）
        InstallTeam team = registerTeam("CERT-EDGE", today);
        keepOnly(team);
        PostingOrder order = createOrder(registerSite(true), today);

        InstallDispatch d = dispatchOf(order);
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, d.getStatus());
        assertEquals(team.getTeamNo(), d.getTeamNo());
    }

    @Test
    void sameTeamSameSlot_walksDaysAndSlots() {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        LocalDate monday = WeekRanges.mondayOf(LocalDate.now());

        InstallDispatch d1 = dispatchOf(createOrder(registerSite(false), monday));
        InstallDispatch d2 = dispatchOf(createOrder(registerSite(false), monday));
        InstallDispatch d3 = dispatchOf(createOrder(registerSite(false), monday));

        // 一个队同一时段只排一个点位：周一上午 → 周一下午 → 周二上午
        assertEquals(monday, d1.getPlannedDate());
        assertEquals(InstallEnums.SLOT_AM, d1.getTimeSlot());
        assertEquals(monday, d2.getPlannedDate());
        assertEquals(InstallEnums.SLOT_PM, d2.getTimeSlot());
        assertEquals(monday.plusDays(1), d3.getPlannedDate());
        assertEquals(InstallEnums.SLOT_AM, d3.getTimeSlot());
    }

    @Test
    void weekSlotsExhausted_staysPending() {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        LocalDate sunday = WeekRanges.sundayOf(LocalDate.now());

        dispatchOf(createOrder(registerSite(false), sunday));
        dispatchOf(createOrder(registerSite(false), sunday));
        InstallDispatch d3 = dispatchOf(createOrder(registerSite(false), sunday));

        // 当周只剩周日两个时段，都被占满后第三单待派工，事件里写明原因
        assertEquals(InstallEnums.DISPATCH_PENDING, d3.getStatus());
        assertNull(d3.getTeamNo());
        DispatchDetailView detail = dispatchService.detail(d3.getDispatchNo());
        assertEquals(1, detail.getEvents().size());
        assertEquals(InstallEnums.EVENT_CREATE, detail.getEvents().get(0).getEventType());
        assertTrue(detail.getEvents().get(0).getDetail().contains("未匹配到"));
    }

    @Test
    void manualAssign_afterTeamRegistered() throws Exception {
        keepOnly();
        LocalDate today = LocalDate.now();
        PostingOrder order = createOrder(registerSite(false), today);
        InstallDispatch d = dispatchOf(order);
        // 没有在册队：待派工
        assertEquals(InstallEnums.DISPATCH_PENDING, d.getStatus());

        InstallTeam team = registerTeam(null, null);
        AssignRequest assign = new AssignRequest();
        assign.setTeamNo(team.getTeamNo());
        assign.setPlannedDate(today);
        assign.setTimeSlot("AM");
        assign.setOperatorName("dispatcher");
        InstallDispatch assigned = dispatchService.assign(d.getDispatchNo(), assign);

        assertEquals(InstallEnums.DISPATCH_ASSIGNED, assigned.getStatus());
        assertEquals(InstallEnums.ASSIGN_MANUAL, assigned.getAssignSource());
        assertEquals(team.getTeamNo(), assigned.getTeamNo());
        DispatchDetailView detail = dispatchService.detail(d.getDispatchNo());
        assertEquals(1, detail.getNotices().size());
        assertEquals(InstallEnums.NOTICE_ASSIGN, detail.getNotices().get(0).getNoticeType());

        // 与当前派工完全一致再派一次：拒
        BusinessException e = assertThrows(BusinessException.class,
                () -> dispatchService.assign(d.getDispatchNo(), assign));
        assertTrue(e.getMessage().contains("无需改派"));
    }

    @Test
    void manualAssign_expiredCertHighAltitude_hardRejected() {
        InstallTeam expired = registerTeam("CERT-EXP", LocalDate.now().minusDays(1));
        keepOnly(expired);
        LocalDate today = LocalDate.now();
        PostingOrder order = createOrder(registerSite(true), today);
        InstallDispatch d = dispatchOf(order);
        assertEquals(InstallEnums.DISPATCH_PENDING, d.getStatus());

        // 高空证过期接不了高空点位：手工派工一样硬拦，没有旁路
        AssignRequest assign = new AssignRequest();
        assign.setTeamNo(expired.getTeamNo());
        assign.setPlannedDate(today);
        assign.setTimeSlot("AM");
        assign.setOperatorName("dispatcher");
        BusinessException e = assertThrows(BusinessException.class,
                () -> dispatchService.assign(d.getDispatchNo(), assign));
        assertTrue(e.getMessage().contains("高空证"));
        assertEquals(InstallEnums.DISPATCH_PENDING, dispatchOf(order).getStatus());
    }

    // ---- 打卡与完工 ----

    @Test
    void checkin_requiresPhoto() throws Exception {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        PostingOrder order = createOrder(registerSite(false), LocalDate.now());
        InstallDispatch d = dispatchOf(order);

        BusinessException e = assertThrows(BusinessException.class,
                () -> dispatchService.checkin(d.getDispatchNo(), new MockMultipartFile[0], "installer1"));
        assertTrue(e.getMessage().contains("照片"));

        dispatchService.checkin(d.getDispatchNo(), photos("checkin.png"), "installer1");
        InstallDispatch arrived = dispatchOf(order);
        assertEquals(InstallEnums.DISPATCH_ARRIVED, arrived.getStatus());
        assertNotNull(arrived.getCheckinTime());
        assertEquals("installer1", arrived.getCheckinOperator());
        assertEquals(InstallEnums.ORDER_INSTALL_INSTALLING, installStatusOf(order));
        DispatchDetailView detail = dispatchService.detail(d.getDispatchNo());
        assertEquals(1, detail.getPhotos().size());
        assertEquals(InstallEnums.PHASE_CHECKIN, detail.getPhotos().get(0).getPhase());
        assertEquals(64, detail.getPhotos().get(0).getFingerprintSha256().length());
    }

    @Test
    void fullFlow_doneReleasesSlotAndCompletesOrder() throws Exception {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        LocalDate monday = WeekRanges.mondayOf(LocalDate.now());
        PostingOrder order = createOrder(registerSite(false), monday);
        InstallDispatch d = dispatchOf(order);

        // 回执说明必填
        dispatchService.checkin(d.getDispatchNo(), photos("checkin.png"), "installer1");
        BusinessException e = assertThrows(BusinessException.class,
                () -> dispatchService.done(d.getDispatchNo(), "  ", null, "installer1"));
        assertTrue(e.getMessage().contains("回执"));

        dispatchService.done(d.getDispatchNo(), "画面已上齐，验收无误", photos("done.png"), "installer1");
        InstallDispatch done = dispatchOf(order);
        assertEquals(InstallEnums.DISPATCH_DONE, done.getStatus());
        assertNotNull(done.getDoneTime());
        assertEquals(InstallEnums.ORDER_INSTALL_INSTALLED, installStatusOf(order));

        // 事件版本链：CREATE v1 → ASSIGN v2 → CHECKIN v3 → DONE v4
        DispatchDetailView detail = dispatchService.detail(d.getDispatchNo());
        List<String> events = detail.getEvents().stream()
                .map(com.evops.install.entity.InstallEvent::getEventType).collect(Collectors.toList());
        assertEquals(Arrays.asList(InstallEnums.EVENT_CREATE, InstallEnums.EVENT_ASSIGN,
                InstallEnums.EVENT_CHECKIN, InstallEnums.EVENT_DONE), events);
        assertEquals(Integer.valueOf(4), detail.getEvents().get(3).getVersionNo());
        assertNotNull(detail.getEvents().get(3).getSnapshot());

        // 完工释放时段：同队同一时段又能排新点位
        InstallDispatch d2 = dispatchOf(createOrder(registerSite(false), monday));
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, d2.getStatus());
        assertEquals(monday, d2.getPlannedDate());
        assertEquals(InstallEnums.SLOT_AM, d2.getTimeSlot());
    }

    // ---- 改期 ----

    @Test
    void reschedule_movesSlotNotifiesAndRequiresReason() {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        LocalDate monday = WeekRanges.mondayOf(LocalDate.now());
        PostingOrder order = createOrder(registerSite(false), monday);
        InstallDispatch d = dispatchOf(order);
        assertEquals(InstallEnums.SLOT_AM, d.getTimeSlot());

        RescheduleRequest bad = new RescheduleRequest();
        bad.setPlannedDate(monday.plusDays(1));
        bad.setTimeSlot("PM");
        bad.setReason("  ");
        bad.setOperatorName("dispatcher");
        assertThrows(BusinessException.class, () -> dispatchService.reschedule(d.getDispatchNo(), bad));

        RescheduleRequest req = new RescheduleRequest();
        req.setPlannedDate(monday.plusDays(1));
        req.setTimeSlot("PM");
        req.setReason("客户要求避开营业高峰");
        req.setOperatorName("dispatcher");
        InstallDispatch moved = dispatchService.reschedule(d.getDispatchNo(), req);
        assertEquals(monday.plusDays(1), moved.getPlannedDate());
        assertEquals(InstallEnums.SLOT_PM, moved.getTimeSlot());

        // 改期要通知到队：通知记录带原因，事件留底
        DispatchDetailView detail = dispatchService.detail(d.getDispatchNo());
        assertEquals(1, detail.getNotices().stream()
                .filter(n -> InstallEnums.NOTICE_RESCHEDULE.equals(n.getNoticeType())).count());
        assertTrue(detail.getNotices().stream()
                .anyMatch(n -> n.getContent().contains("客户要求避开营业高峰")));
        assertTrue(detail.getEvents().stream()
                .anyMatch(ev -> InstallEnums.EVENT_RESCHEDULE.equals(ev.getEventType())));

        // 旧时段已释放：新单同天又排回周一上午
        InstallDispatch d2 = dispatchOf(createOrder(registerSite(false), monday));
        assertEquals(monday, d2.getPlannedDate());
        assertEquals(InstallEnums.SLOT_AM, d2.getTimeSlot());
    }

    // ---- 落架 / 对冲取消 ----

    @Test
    void takeDown_cancelsDispatchNotifiesAndReleasesSlot() {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        LocalDate monday = WeekRanges.mondayOf(LocalDate.now());
        PostingOrder order = createOrder(registerSite(false), monday);
        InstallDispatch d = dispatchOf(order);
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, d.getStatus());

        ActionRequest takeDown = new ActionRequest();
        takeDown.setOperatorName("tester");
        takeDown.setReason("客户撤单");
        orderService.takeDown(order.getOrderNo(), takeDown);

        InstallDispatch canceled = dispatchOf(order);
        assertEquals(InstallEnums.DISPATCH_CANCELED, canceled.getStatus());
        assertTrue(canceled.getCancelReason().contains("客户撤单"));
        assertEquals(InstallEnums.ORDER_INSTALL_CANCELED, installStatusOf(order));
        DispatchDetailView detail = dispatchService.detail(d.getDispatchNo());
        assertTrue(detail.getNotices().stream()
                .anyMatch(n -> InstallEnums.NOTICE_CANCEL.equals(n.getNoticeType())
                        && n.getTeamNo().equals(team.getTeamNo())));
        assertTrue(detail.getEvents().stream()
                .anyMatch(ev -> InstallEnums.EVENT_CANCEL.equals(ev.getEventType())));

        // 时段已释放：新单同周同队又排回周一上午
        InstallDispatch d2 = dispatchOf(createOrder(registerSite(false), monday));
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, d2.getStatus());
        assertEquals(monday, d2.getPlannedDate());
        assertEquals(InstallEnums.SLOT_AM, d2.getTimeSlot());
    }

    @Test
    void offset_cancelsDispatchAndOffsetOrderHasNone() {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        PostingOrder order = createOrder(registerSite(false), LocalDate.now());
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, dispatchOf(order).getStatus());

        ActionRequest offset = new ActionRequest();
        offset.setOperatorName("tester");
        offset.setReason("画面录错");
        PostingOrder offsetOrder = orderService.offset(order.getOrderNo(), offset);

        assertEquals(InstallEnums.DISPATCH_CANCELED, dispatchOf(order).getStatus());
        assertEquals(InstallEnums.ORDER_INSTALL_CANCELED, installStatusOf(order));
        // 对冲单是更正凭证，不生成安装派单
        assertNull(dispatchService.findByOrderNo(offsetOrder.getOrderNo()));
    }

    // ---- 资质版本 ----

    @Test
    void renew_newVersionChainAndRematch() {
        InstallTeam team = registerTeam("CERT-OLD", LocalDate.now().minusDays(1));
        keepOnly(team);
        LocalDate today = LocalDate.now();
        // 证已过期：高空点位派不出去
        InstallDispatch d1 = dispatchOf(createOrder(registerSite(true), today));
        assertEquals(InstallEnums.DISPATCH_PENDING, d1.getStatus());

        QualRenewRequest renew = new QualRenewRequest();
        renew.setCertNo("CERT-NEW");
        renew.setCertValidUntil(today.plusDays(30));
        renew.setOperatorName("safety");
        renew.setRemark("换证复审通过");
        InstallTeam renewed = teamService.renew(team.getTeamNo(), renew);
        assertEquals(Integer.valueOf(2), renewed.getCurrentQualVersionNo());
        assertEquals("CERT-NEW", renewed.getCertNo());
        assertEquals(InstallEnums.QUAL_VALID, renewed.getQualStatus());

        List<InstallQualVersion> versions = teamService.qualVersions(team.getTeamNo());
        assertEquals(2, versions.size());
        assertEquals(InstallEnums.QUAL_INITIAL, versions.get(0).getChangeType());
        assertEquals(InstallEnums.QUAL_VERSION_SUPERSEDED, versions.get(0).getStatus());
        assertEquals(InstallEnums.QUAL_RENEW, versions.get(1).getChangeType());
        assertEquals(InstallEnums.QUAL_VERSION_EFFECTIVE, versions.get(1).getStatus());

        // 换证后新单能派了，且派单落的是 v2 资质快照
        InstallDispatch d2 = dispatchOf(createOrder(registerSite(true), today));
        assertEquals(InstallEnums.DISPATCH_ASSIGNED, d2.getStatus());
        assertEquals(Integer.valueOf(2), d2.getMatchedQualVersionNo());
        assertEquals("CERT-NEW", d2.getMatchedCertNo());
        assertEquals(today.plusDays(30), d2.getMatchedCertValidUntil());
    }

    // ---- 月度报表 ----

    @Test
    void monthlyReport_doneOntimeLateAndCanceled() throws Exception {
        InstallTeam teamX = registerTeam(null, null);
        InstallTeam teamY = registerTeam(null, null);
        keepOnly(teamX, teamY);
        LocalDate today = LocalDate.now();

        // teamX：准时一单（今天计划今天完工）、超期一单（三天前计划今天完工）
        PostingOrder ontime = createOrder(registerSite(false), today);
        dispatchService.checkin(dispatchOf(ontime).getDispatchNo(), photos("a.png"), "installer1");
        dispatchService.done(dispatchOf(ontime).getDispatchNo(), "完工", null, "installer1");

        PostingOrder late = createOrder(registerSite(false), today.minusDays(3));
        dispatchService.checkin(dispatchOf(late).getDispatchNo(), photos("b.png"), "installer1");
        dispatchService.done(dispatchOf(late).getDispatchNo(), "完工", null, "installer1");

        // teamY：一派即撤（落架带动取消）
        keepOnly(teamY);
        PostingOrder canceled = createOrder(registerSite(false), today);
        assertEquals(teamY.getTeamNo(), dispatchOf(canceled).getTeamNo());
        ActionRequest takeDown = new ActionRequest();
        takeDown.setOperatorName("tester");
        orderService.takeDown(canceled.getOrderNo(), takeDown);

        String period = YearMonth.now().toString();
        List<TeamReportRow> rows = reportService.teamReport(period);
        TeamReportRow rowX = rows.stream().filter(r -> r.getTeamNo().equals(teamX.getTeamNo()))
                .findFirst().orElseThrow(() -> new AssertionError("报表里没有 teamX"));
        assertEquals(2, rowX.getDoneCount());
        assertEquals(1, rowX.getOntimeCount());
        assertEquals(1, rowX.getLateCount());
        assertEquals(new BigDecimal("50.0"), rowX.getOntimeRate());

        TeamReportRow rowY = rows.stream().filter(r -> r.getTeamNo().equals(teamY.getTeamNo()))
                .findFirst().orElseThrow(() -> new AssertionError("报表里没有 teamY"));
        assertEquals(0, rowY.getDoneCount());
        assertEquals(1, rowY.getCanceledCount());
        assertNull(rowY.getOntimeRate());

        String csv = reportService.teamReportCsv(period);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains(teamX.getTeamNo()));
    }

    // ---- 照片冻结 ----

    @Test
    void photoDelete_frozenUntilClosed() throws Exception {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        PostingOrder order = createOrder(registerSite(false), LocalDate.now());
        InstallDispatch d = dispatchOf(order);
        dispatchService.checkin(d.getDispatchNo(), photos("checkin.png"), "installer1");

        Long photoId = dispatchService.detail(d.getDispatchNo()).getPhotos().get(0).getId();
        // 派单未关闭：留证照片不许删
        assertThrows(BusinessException.class, () -> dispatchService.deletePhoto(photoId, "tester"));

        dispatchService.done(d.getDispatchNo(), "完工", null, "installer1");
        // 关闭后允许删，删一张写一条事件
        dispatchService.deletePhoto(photoId, "tester");
        DispatchDetailView detail = dispatchService.detail(d.getDispatchNo());
        assertTrue(detail.getPhotos().isEmpty());
        assertTrue(detail.getEvents().stream()
                .anyMatch(ev -> InstallEnums.EVENT_PHOTO_DELETE.equals(ev.getEventType())));
    }

    // ---- 关闭即冻结 ----

    @Test
    void closedDispatch_rejectsFurtherOps() throws Exception {
        InstallTeam team = registerTeam(null, null);
        keepOnly(team);
        LocalDate today = LocalDate.now();
        PostingOrder order = createOrder(registerSite(false), today);
        InstallDispatch d = dispatchOf(order);
        dispatchService.checkin(d.getDispatchNo(), photos("checkin.png"), "installer1");
        dispatchService.done(d.getDispatchNo(), "完工", null, "installer1");

        RescheduleRequest reschedule = new RescheduleRequest();
        reschedule.setPlannedDate(today.plusDays(1));
        reschedule.setTimeSlot("PM");
        reschedule.setReason("已完工还改期");
        reschedule.setOperatorName("dispatcher");
        assertThrows(BusinessException.class, () -> dispatchService.reschedule(d.getDispatchNo(), reschedule));
        assertThrows(BusinessException.class,
                () -> dispatchService.checkin(d.getDispatchNo(), photos("again.png"), "installer1"));
        assertThrows(BusinessException.class,
                () -> dispatchService.done(d.getDispatchNo(), "重复完工", null, "installer1"));
    }
}
