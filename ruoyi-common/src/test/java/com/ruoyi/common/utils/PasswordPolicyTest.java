package com.ruoyi.common.utils;

import com.ruoyi.common.exception.ServiceException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PasswordPolicyTest
{
    @Test public void acceptsPassphrasesAndPreservesSpaces()
    {
        assertNull(PasswordPolicy.validationError("alice", "Seven stars over Lima!"));
        assertNull(PasswordPolicy.validationError("alice", "  Seven stars over Lima!  "));
        assertNull(PasswordPolicy.validationError("alice", "雨后山谷的微风与窗边温热的红茶"));
        assertNull(PasswordPolicy.validationError("alice", "a1B2c3D4e5F6g7H"));
    }

    @Test public void rejectsShortLongWeakAndContextualPasswords()
    {
        for (String password : new String[] { null, "", "short", repeat("abC2", 17), "passwordpassword", "AdMiN1234567890!", "ruoyi1234567890!", repeat("a", 15), repeat("ab12", 4), repeat(" ", 15), "abcdefghijklmnop" })
            assertNotNull("Weak password must be rejected", PasswordPolicy.validationError("admin", password));
    }

    @Test public void checksUtf8BytesAndUnicodeCodePoints()
    {
        String sixtyFour = "Ab8kL2mN9pQrS5tU7vWxY3zE6fGhJ4cD1" + "Uv9pQ2rS8tWxY5zA6bCdE3fG7hJkL4m";
        assertEquals(64, sixtyFour.length());
        assertNull(PasswordPolicy.validationError("alice", sixtyFour));
        assertNotNull(PasswordPolicy.validationError("alice", sixtyFour + "x"));
        assertNotNull(PasswordPolicy.validationError("alice", repeat("汉", 25)));
        String withEmoji = sixtyFour.substring(0, 62) + "😀😺";
        assertEquals(64, withEmoji.codePointCount(0, withEmoji.length()));
        assertNull(PasswordPolicy.validationError("alice", withEmoji));
        assertTrue(PasswordPolicy.isSupportedLoginPassword(withEmoji));
        assertNotNull(PasswordPolicy.validationError("alice", "Seven stars over Lima\u0000"));
        assertNotNull(PasswordPolicy.validationError("alice", "Seven stars over Lima\uD800"));
    }

    @Test public void doesNotBlockExistingWeakPasswordsAtLogin()
    {
        assertTrue(PasswordPolicy.isSupportedLoginPassword("admin123"));
        assertNotNull(PasswordPolicy.validationError("admin", "admin123"));
        assertFalse(PasswordPolicy.isSupportedLoginPassword(repeat("汉", 25)));
    }

    @Test public void reportsBusinessValidationErrorsWithoutEchoingPassword()
    {
        try { PasswordPolicy.validate("alice", "passwordpassword"); fail("Expected validation failure"); }
        catch (ServiceException error) {
            assertEquals(Integer.valueOf(400), error.getCode());
            assertFalse(error.getMessage().contains("passwordpassword"));
        }
        assertEquals(15, PasswordPolicy.describe().get("minLength"));
    }

    @Test public void hashesAndVerifiesWholeLongPassword()
    {
        String password = "Seven stars over Lima!";
        String encoded = SecurityUtils.encryptPassword(password);
        assertTrue(SecurityUtils.matchesPassword(password, encoded));
        assertFalse(SecurityUtils.matchesPassword(password + "!", encoded));
    }

    private static String repeat(String value, int times)
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < times; i++) result.append(value);
        return result.toString();
    }
}
