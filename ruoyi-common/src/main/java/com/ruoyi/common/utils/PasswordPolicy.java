package com.ruoyi.common.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.exception.ServiceException;

/** New-password policy. Login keeps accepting existing passwords. Never alters password text. */
public final class PasswordPolicy
{
    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 64;
    public static final int MAX_UTF8_BYTES = 72;
    public static final String DESCRIPTION = "新密码须为15～64个字符，UTF-8不超过72字节；不能使用常见弱口令、简单重复或用户名派生密码，可使用空格和中文。";
    private static final List<String> CONTEXT_WORDS = Collections.unmodifiableList(Arrays.asList("ruoyi", "xycore", "deepresearch"));
    private static final Set<String> BLOCKLIST = loadBlocklist();

    private PasswordPolicy() {}

    private static Set<String> loadBlocklist()
    {
        InputStream input = PasswordPolicy.class.getResourceAsStream("/security/weak-passwords.txt");
        if (input == null) throw new IllegalStateException("Password blocklist is missing");
        Set<String> result = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (!line.isEmpty() && !line.startsWith("#")) result.add(canonical(line));
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot read password blocklist", e);
        }
        return Collections.unmodifiableSet(result);
    }

    private static String canonical(String value)
    {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
    }

    private static String letters(String value)
    {
        return canonical(value).replaceAll("[\\p{N}\\p{P}\\p{S}\\p{Z}\\s]", "");
    }

    public static String validationError(String username, String password)
    {
        if (password == null || password.isEmpty()) return "密码不能为空";
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) return "新密码长度必须为15～64个字符";
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) return "密码UTF-8编码不能超过72字节，请减少中文或表情字符数量";
        int[] points = password.codePoints().toArray();
        for (int point : points)
        {
            if (Character.isISOControl(point) || (point >= 0xD800 && point <= 0xDFFF)) return "密码不能包含控制字符或无效Unicode字符";
        }
        String normalized = canonical(password);
        String base = letters(password);
        if (normalized.isEmpty() || BLOCKLIST.contains(normalized) || (!base.isEmpty() && BLOCKLIST.contains(base)))
            return "密码属于常见弱口令，请使用更难猜测的密码或长口令";
        if (username != null && !letters(username).isEmpty() && base.equals(letters(username)))
            return "密码不能由用户名加数字或符号构成";
        if (CONTEXT_WORDS.contains(base)) return "密码不能由系统名称加数字或符号构成";
        for (int period = 1; period <= 4; period++)
        {
            boolean repeated = points.length % period == 0;
            for (int index = period; repeated && index < points.length; index++) repeated = points[index] == points[index % period];
            if (repeated) return "密码过于简单，请避免重复的短字符组合";
        }
        return null;
    }

    public static void validate(String username, String password)
    {
        String error = validationError(username, password);
        if (error != null) throw new ServiceException(error, 400);
    }

    /** Length protection only: legacy passwords are not subjected to the new policy at login. */
    public static boolean isSupportedLoginPassword(String password)
    {
        if (password == null) return false;
        int length = password.codePointCount(0, password.length());
        return length >= UserConstants.PASSWORD_MIN_LENGTH && length <= MAX_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    public static Map<String, Object> describe()
    {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("minLength", MIN_LENGTH);
        policy.put("maxLength", MAX_LENGTH);
        policy.put("maxUtf8Bytes", MAX_UTF8_BYTES);
        policy.put("description", DESCRIPTION);
        policy.put("blockedPasswords", BLOCKLIST);
        policy.put("contextWords", CONTEXT_WORDS);
        return policy;
    }
}
