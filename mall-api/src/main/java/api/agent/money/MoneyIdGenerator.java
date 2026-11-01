package api.agent.money;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 资金异常调查业务标识符生成器
 * <p>
 * 契约规范：
 * - caseNo: MC + 32 位大写十六进制 (总长 34)
 * - taskNo: MT + 32 位大写十六进制 (总长 34)
 * - dispatchNo: MD + 32 位大写十六进制 (总长 34)
 * - reportNo: MR + 32 位大写十六进制 (总长 34)
 * - inputRequestNo: MI + 32 位大写十六进制 (总长 34)
 * </p>
 */
public final class MoneyIdGenerator {

    private static final Pattern HEX32_PATTERN = Pattern.compile("^[0-9A-F]{32}$");
    private static final Pattern CASE_NO_PATTERN = Pattern.compile("^MC[0-9A-F]{32}$");
    private static final Pattern TASK_NO_PATTERN = Pattern.compile("^MT[0-9A-F]{32}$");
    private static final Pattern DISPATCH_NO_PATTERN = Pattern.compile("^MD[0-9A-F]{32}$");
    private static final Pattern REPORT_NO_PATTERN = Pattern.compile("^MR[0-9A-F]{32}$");
    private static final Pattern INPUT_REQUEST_NO_PATTERN = Pattern.compile("^MI[0-9A-F]{32}$");

    private MoneyIdGenerator() {}

    private static String randomHex32() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    public static String generateCaseNo() {
        return "MC" + randomHex32();
    }

    public static String generateTaskNo() {
        return "MT" + randomHex32();
    }

    public static String generateDispatchNo() {
        return "MD" + randomHex32();
    }

    public static String generateReportNo() {
        return "MR" + randomHex32();
    }

    public static String generateInputRequestNo() {
        return "MI" + randomHex32();
    }

    public static String generateContextId() {
        return "CTX_" + randomHex32();
    }

    public static String generateGrantNo() {
        return "GRANT_" + randomHex32();
    }

    public static boolean isValidCaseNo(String caseNo) {
        return caseNo != null && CASE_NO_PATTERN.matcher(caseNo).matches();
    }

    public static boolean isValidTaskNo(String taskNo) {
        return taskNo != null && TASK_NO_PATTERN.matcher(taskNo).matches();
    }

    public static boolean isValidDispatchNo(String dispatchNo) {
        return dispatchNo != null && DISPATCH_NO_PATTERN.matcher(dispatchNo).matches();
    }

    public static boolean isValidReportNo(String reportNo) {
        return reportNo != null && REPORT_NO_PATTERN.matcher(reportNo).matches();
    }

    public static boolean isValidInputRequestNo(String inputRequestNo) {
        return inputRequestNo != null && INPUT_REQUEST_NO_PATTERN.matcher(inputRequestNo).matches();
    }
}

