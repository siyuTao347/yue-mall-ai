package com.example.agent.customer.richui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import static com.example.agent.customer.CustomerJson.*;

@Component
public class RichMessageAssembler {

    public ObjectNode buildProductCard(String itemId, String itemName, String price, Integer stock, String imageUrl) {
        ObjectNode card = object();
        card.put("cardId", id("CARD"));
        card.put("type", "product_showcase");
        card.put("cardVersion", 1);
        card.put("revision", 1);
        card.put("fallbackText", "推荐商品：" + itemName + "，价格：" + price);

        ObjectNode data = object();
        ArrayNode items = array();
        ObjectNode item = object()
                .put("itemId", itemId)
                .put("name", itemName)
                .put("price", price)
                .put("currency", "CNY");
        if (stock != null) item.put("stock", stock);
        if (imageUrl != null) item.put("imageUrl", imageUrl);
        items.add(item);
        data.set("items", items);
        card.set("data", data);

        ArrayNode actions = array();
        actions.add(object()
                .put("actionId", id("ACT"))
                .put("type", "OPEN_BUSINESS_PAGE")
                .put("label", "查看商品详情")
                .set("target", object().put("routeName", "ITEM_DETAIL").put("bizNo", itemId)));
        card.set("actions", actions);
        return card;
    }

    public ObjectNode buildOrderTimelineCard(String orderNo, String status, ArrayNode timelineItems) {
        ObjectNode card = object();
        card.put("cardId", id("CARD"));
        card.put("type", "order_timeline");
        card.put("cardVersion", 1);
        card.put("revision", 1);
        card.put("fallbackText", "订单 " + orderNo + " 当前状态：" + status);

        ObjectNode data = object();
        data.put("orderNo", orderNo);
        data.put("timelineKind", "ORDER_STATUS");
        data.set("items", timelineItems != null ? timelineItems : array());
        card.set("data", data);

        ArrayNode actions = array();
        actions.add(object()
                .put("actionId", id("ACT"))
                .put("type", "OPEN_BUSINESS_PAGE")
                .put("label", "进入交易工作台")
                .set("target", object().put("routeName", "TRADE_ORDER_DETAIL").put("bizNo", orderNo)));
        card.set("actions", actions);
        return card;
    }

    public ObjectNode buildRatingCard(String scope, String targetNo) {
        ObjectNode card = object();
        card.put("cardId", id("CARD"));
        card.put("type", "rating");
        card.put("cardVersion", 1);
        card.put("revision", 1);
        card.put("fallbackText", "请为本次服务评分（1-5星）");

        ObjectNode data = object();
        data.put("feedbackScope", scope != null ? scope : "RUN");
        data.put("targetNo", targetNo != null ? targetNo : "");
        data.put("min", 1);
        data.put("max", 5);
        card.set("data", data);

        ArrayNode actions = array();
        actions.add(object()
                .put("actionId", id("ACT"))
                .put("type", "SUBMIT_RATING")
                .put("label", "提交评价"));
        card.set("actions", actions);
        return card;
    }

    public ObjectNode buildDynamicFormCard(String workflowNo, String taskNo, String formInstanceId, int taskVersion, ArrayNode fields) {
        ObjectNode card = object();
        card.put("cardId", id("CARD"));
        card.put("type", "dynamic_form");
        card.put("cardVersion", 1);
        card.put("revision", 1);
        card.put("fallbackText", "请点击填写并提交售后申请表单");

        ObjectNode data = object();
        data.put("workflowNo", workflowNo);
        data.put("taskNo", taskNo);
        data.put("formInstanceId", formInstanceId);
        data.put("taskVersion", taskVersion);
        data.set("fields", fields != null ? fields : array());
        card.set("data", data);

        ArrayNode actions = array();
        actions.add(object()
                .put("actionId", id("ACT"))
                .put("type", "SUBMIT_FORM")
                .put("label", "提交资料"));
        card.set("actions", actions);
        return card;
    }
}
