package com.evops.install.controller;

import com.evops.common.ApiResponse;
import com.evops.install.dto.AssignRequest;
import com.evops.install.dto.DispatchDetailView;
import com.evops.install.dto.RescheduleRequest;
import com.evops.install.dto.TeamReportRow;
import com.evops.install.entity.InstallDispatch;
import com.evops.install.entity.InstallPhoto;
import com.evops.install.service.InstallDispatchService;
import com.evops.install.service.InstallReportService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 安装派单：翻查、全量档案、手工派工/改派、改期（通知到队）、到场打卡（照片必填）、
 * 完工回执、照片下载与关闭后删除、月度完工量准时率报表。
 */
@RestController
@RequestMapping("/api/install")
public class InstallDispatchController {

    private final InstallDispatchService dispatchService;
    private final InstallReportService reportService;

    public InstallDispatchController(InstallDispatchService dispatchService, InstallReportService reportService) {
        this.dispatchService = dispatchService;
        this.reportService = reportService;
    }

    /** 派单翻查：?status=&teamNo=&siteNo=&keyword= */
    @GetMapping("/dispatches")
    public ApiResponse<List<InstallDispatch>> list(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String teamNo,
                                                   @RequestParam(required = false) String siteNo,
                                                   @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(dispatchService.list(status, teamNo, siteNo, keyword));
    }

    /** 派单全量档案：单据 + 事件版本 + 照片 + 到队通知记录。 */
    @GetMapping("/dispatches/{dispatchNo}")
    public ApiResponse<DispatchDetailView> detail(@PathVariable String dispatchNo) {
        return ApiResponse.ok(dispatchService.detail(dispatchNo));
    }

    /** 上刊单查派单：业务方不用再打电话问调度。 */
    @GetMapping("/orders/{orderNo}/dispatch")
    public ApiResponse<InstallDispatch> byOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(dispatchService.findByOrderNo(orderNo));
    }

    /** 手工派工/改派：与自动匹配同一套资质+档期校验，过期证接高空点位硬拦。 */
    @PostMapping("/dispatches/{dispatchNo}/assign")
    public ApiResponse<InstallDispatch> assign(@PathVariable String dispatchNo,
                                               @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(dispatchService.assign(dispatchNo, request));
    }

    /** 改期：原因必填，重校档期，通知到队。 */
    @PostMapping("/dispatches/{dispatchNo}/reschedule")
    public ApiResponse<InstallDispatch> reschedule(@PathVariable String dispatchNo,
                                                   @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.ok(dispatchService.reschedule(dispatchNo, request));
    }

    /** 师傅到场拍照打卡：照片必填（没照片不算打卡）。 */
    @PostMapping(value = "/dispatches/{dispatchNo}/checkin", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InstallDispatch> checkin(@PathVariable String dispatchNo,
                                                @RequestParam(value = "operatorName", required = false) String operatorName,
                                                @RequestParam("photos") MultipartFile[] photos) {
        return ApiResponse.ok(dispatchService.checkin(dispatchNo, photos, operatorName));
    }

    /** 完工回传回执：回执说明必填，完工照片可选。 */
    @PostMapping(value = "/dispatches/{dispatchNo}/done", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InstallDispatch> done(@PathVariable String dispatchNo,
                                             @RequestParam("receiptContent") String receiptContent,
                                             @RequestParam(value = "operatorName", required = false) String operatorName,
                                             @RequestParam(value = "photos", required = false) MultipartFile[] photos) {
        return ApiResponse.ok(dispatchService.done(dispatchNo, receiptContent, photos, operatorName));
    }

    /** 下载现场照片。 */
    @GetMapping("/photos/{photoId}/download")
    public ResponseEntity<ByteArrayResource> downloadPhoto(@PathVariable Long photoId) {
        InstallPhoto photo = dispatchService.requirePhoto(photoId);
        byte[] data = dispatchService.readPhotoBytes(photo);
        MediaType mediaType = photo.getContentType() != null
                ? MediaType.parseMediaType(photo.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(photo.getFileName()))
                .contentType(mediaType)
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    /** 派单关闭后才允许删照片，且删一条留一条事件；关闭前调用直接被拒。 */
    @PostMapping("/photos/{photoId}/delete")
    public ApiResponse<Void> deletePhoto(@PathVariable Long photoId,
                                         @RequestParam(required = false) String operatorName) {
        dispatchService.deletePhoto(photoId, operatorName);
        return ApiResponse.ok(null);
    }

    /** 月度安装队报表：完工量与准时率（结算认这个数）。 */
    @GetMapping("/reports/teams")
    public ApiResponse<List<TeamReportRow>> teamReport(@RequestParam String period) {
        return ApiResponse.ok(reportService.teamReport(period));
    }

    /** 月度报表整表导出 CSV（带 BOM）。 */
    @GetMapping("/reports/teams/export")
    public ResponseEntity<String> teamReportExport(@RequestParam String period) {
        String csv = reportService.teamReportCsv(period);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("install-team-report-" + period + ".csv"))
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    private String contentDisposition(String filename) {
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不受支持", e);
        }
        return "attachment; filename=\"" + filename.replaceAll("[\"\\\\]", "_") + "\"; filename*=UTF-8''" + encoded;
    }
}
