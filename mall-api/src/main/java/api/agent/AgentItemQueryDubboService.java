package api.agent;

import java.io.Serializable;
import java.math.BigDecimal;

/** Read-only public product facts exposed to the customer-service agent. */
public interface AgentItemQueryDubboService {
    PublicItemFact queryPublicItem(long itemId);

    class PublicItemFact implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long itemId;
        private String itemName;
        private BigDecimal price;
        private Integer availableStock;
        private String deliveryMode;
        private String subTitle;

        public PublicItemFact() {}

        public PublicItemFact(Long itemId, String itemName, BigDecimal price, Integer availableStock,
                              String deliveryMode, String subTitle) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.price = price;
            this.availableStock = availableStock;
            this.deliveryMode = deliveryMode;
            this.subTitle = subTitle;
        }

        public Long itemId() { return itemId; }
        public String itemName() { return itemName; }
        public BigDecimal price() { return price; }
        public Integer availableStock() { return availableStock; }
        public String deliveryMode() { return deliveryMode; }
        public String subTitle() { return subTitle; }

        public Long getItemId() { return itemId; }
        public void setItemId(Long itemId) { this.itemId = itemId; }
        public String getItemName() { return itemName; }
        public void setItemName(String itemName) { this.itemName = itemName; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public Integer getAvailableStock() { return availableStock; }
        public void setAvailableStock(Integer availableStock) { this.availableStock = availableStock; }
        public String getDeliveryMode() { return deliveryMode; }
        public void setDeliveryMode(String deliveryMode) { this.deliveryMode = deliveryMode; }
        public String getSubTitle() { return subTitle; }
        public void setSubTitle(String subTitle) { this.subTitle = subTitle; }
    }
}
