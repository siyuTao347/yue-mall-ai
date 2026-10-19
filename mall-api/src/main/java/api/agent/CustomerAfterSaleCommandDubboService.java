package api.agent;

import java.io.Serializable;

/**
 * 智能客服 SOP / 售后业务命令受限委托 Dubbo 契约
 * 遵循 operationId 严格幂等保障
 */
public interface CustomerAfterSaleCommandDubboService {

    AfterSaleCommandResult createAfterSaleRequest(String operationId, String authorizationContextJson, String requestJson);

    AfterSaleCommandResult submitAdditionalEvidence(String operationId, String authorizationContextJson, String disputeNo, String evidenceRefsJson);

    AfterSaleCommandResult submitReviewDecision(String operationId, String authorizationContextJson, String disputeNo, String expectedBizVersion, String decision);

    AfterSaleCommandResult queryCommandResult(String operationId, String authorizationContextJson);

    class AfterSaleCommandResult implements Serializable {
        private static final long serialVersionUID = 1L;
        private boolean success;
        private String disputeNo;
        private String state;
        private String errorCode;
        private String errorMessage;
        private String detailsJson;

        public AfterSaleCommandResult() {}

        public AfterSaleCommandResult(boolean success, String disputeNo, String state, String errorCode, String errorMessage, String detailsJson) {
            this.success = success;
            this.disputeNo = disputeNo;
            this.state = state;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.detailsJson = detailsJson;
        }

        public static AfterSaleCommandResult ok(String disputeNo, String state, String detailsJson) {
            return new AfterSaleCommandResult(true, disputeNo, state, null, null, detailsJson);
        }

        public static AfterSaleCommandResult fail(String errorCode, String errorMessage) {
            return new AfterSaleCommandResult(false, null, "FAILED", errorCode, errorMessage, null);
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getDisputeNo() { return disputeNo; }
        public void setDisputeNo(String disputeNo) { this.disputeNo = disputeNo; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getDetailsJson() { return detailsJson; }
        public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }
    }
}
