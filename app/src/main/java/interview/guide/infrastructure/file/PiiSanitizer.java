package interview.guide.infrastructure.file;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII（个人可识别信息）脱敏服务
 * <p>
 * 对简历文本中的手机号、邮箱、身份证号、银行卡号进行掩码替换，
 * 防止敏感信息在发送给第三方 LLM 服务时泄露。
 * <p>
 * 掩码策略：保留首尾字符，中间替换为星号（保留上下文语义，LLM 仍可识别字段类型）。
 */
@Service
public class PiiSanitizer {

    /**
     * 中国手机号：支持 +86 前缀、空格/横线分隔、连续 11 位
     * 匹配：13812345678 / +86 13812345678 / 138-1234-5678
     */
    private static final Pattern PHONE_CN = Pattern.compile(
        "(?:\\+?86[-\\s]?)?1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}");

    /**
     * 邮箱地址
     */
    private static final Pattern EMAIL = Pattern.compile(
        "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /**
     * 身份证号（18 位）：严格校验日期部分，避免误匹配银行卡号
     * 格式：6 位地区码 + 8 位出生日期（19xx/20xx 年 + 合法月日）+ 3 位顺序码 + 1 位校验码
     */
    private static final Pattern ID_CARD = Pattern.compile(
        "\\b[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b");

    /**
     * 银行卡号：16-19 位连续数字（可能含空格分隔）
     * 排除已匹配的身份证号（通过处理顺序保证）
     */
    private static final Pattern BANK_CARD = Pattern.compile(
        "\\b\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{0,3}\\b");

    /**
     * 对文本进行 PII 脱敏。
     * <p>
     * 替换顺序：身份证 → 手机号 → 邮箱 → 银行卡
     * （身份证正则最严格，先匹配避免被手机号正则部分命中）
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        result = maskPattern(result, ID_CARD, this::maskIdCard);
        result = maskPattern(result, PHONE_CN, this::maskPhone);
        result = maskPattern(result, EMAIL, this::maskEmail);
        result = maskPattern(result, BANK_CARD, this::maskBankCard);
        return result;
    }

    private String maskPattern(String text, Pattern pattern,
                               java.util.function.Function<String, String> masker) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masker.apply(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 手机号掩码：138****5678
     */
    private String maskPhone(String phone) {
        String digits = phone.replaceAll("[^\\d]", "");
        if (digits.length() < 11) {
            return phone;
        }
        String normalized = digits.substring(digits.length() - 11);
        String prefix = phone.substring(0, phone.indexOf(normalized.charAt(0)));
        return prefix + normalized.substring(0, 3) + "****" + normalized.substring(7);
    }

    /**
     * 邮箱掩码：z***n@example.com（用户名保留首尾，域名完整保留）
     */
    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return email;
        }
        String username = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (username.length() <= 2) {
            return username.charAt(0) + "***" + domain;
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1) + domain;
    }

    /**
     * 身份证号掩码：110101********1234（保留前 6 位地区码 + 后 4 位）
     */
    private String maskIdCard(String idCard) {
        if (idCard.length() < 18) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(14);
    }

    /**
     * 银行卡号掩码：6222 **** **** 1234（保留前 4 位 + 后 4 位）
     */
    private String maskBankCard(String cardNo) {
        String digits = cardNo.replaceAll("[^\\d]", "");
        if (digits.length() < 8) {
            return cardNo;
        }
        return digits.substring(0, 4) + " **** **** " + digits.substring(digits.length() - 4);
    }
}
