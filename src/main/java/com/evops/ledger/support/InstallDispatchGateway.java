package com.evops.ledger.support;

import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;

/**
 * 上刊单与安装派单域之间的端口（接口定义在底账侧，由 install 域提供实现）。
 *
 * 这样底账域不编译依赖安装域，避免循环依赖；安装模块未启用（无实现）时，
 * {@code OrderService} 经 ObjectProvider 取不到实现，上刊流程与 F1 完全一致。
 *
 * 口径：上刊单确认（开单）后自动生成安装派单并按资质+档期匹配安装队；
 * 落架/对冲时取消在途派单并通知到队；上刊单 install_status 随派单流转。
 */
public interface InstallDispatchGateway {

    /**
     * 上刊单确认（开单）后调用：为单据生成安装派单并尝试自动匹配安装队。
     * 无合格安装队/时段时派单停 PENDING 待派工，不抛异常、不影响开单。
     *
     * @param order 已落库、已占档期的上刊单（POSTING 单；对冲单不生成派单）
     * @param site  单据点位（高空点位标记随派单快照固化）
     */
    void onPostingCreated(PostingOrder order, Site site);

    /**
     * 上刊单落架/对冲关闭时调用：取消该单在途的安装派单（释放时段、通知到队），
     * 上刊单 install_status 置 CANCELED；没有派单的单据内部直接跳过。
     *
     * @param order    被关闭的上刊单
     * @param operator 经手人
     * @param reason   关闭原因（落架/对冲说明）
     */
    void onPostingClosed(PostingOrder order, String operator, String reason);
}
