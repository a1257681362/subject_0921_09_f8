package com.evops.install.service;

import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.support.InstallDispatchGateway;
import org.springframework.stereotype.Component;

/**
 * 安装派单域对底账域的网关实现：上刊单确认生成派单、落架/对冲取消派单。
 * 与 OrderService 同事务——单据、档期、派单同生共死，不留半截。
 */
@Component
public class InstallDispatchGatewayImpl implements InstallDispatchGateway {

    private final InstallDispatchService dispatchService;

    public InstallDispatchGatewayImpl(InstallDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public void onPostingCreated(PostingOrder order, Site site) {
        dispatchService.generateFor(order, site);
    }

    @Override
    public void onPostingClosed(PostingOrder order, String operator, String reason) {
        dispatchService.cancelFor(order, operator, reason);
    }
}
