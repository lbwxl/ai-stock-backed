package com.ruoyi.web;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import org.junit.Test;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.RegisterBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysRegisterService;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import static org.junit.Assert.*;

/** Exercise registration/import validation without enabling public registration or writing real users. */
public class PasswordEntryPointsTest
{
    @Test public void registrationRejectsWeakPasswordsWithoutReachingPersistence() throws Exception
    {
        SysRegisterService service = new SysRegisterService();
        set(service, "configService", config("unused"));
        for (String password : new String[] { "short", "passwordpassword", "registercase123456789", "aaaaaaaaaaaaaaaa", "汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉" })
        {
            RegisterBody body = new RegisterBody();
            body.setUsername("registercase");
            body.setPassword(password);
            // userService intentionally absent: validation must happen before any persistence lookup.
            String error = service.register(body);
            assertNotNull(error);
            assertFalse(error.isEmpty());
            assertFalse(error.contains(password));
        }
    }

    @Test public void importRejectsWeakDefaultPasswordBeforeInsertingUsers() throws Exception
    {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory())
        {
            SysUserServiceImpl service = new SysUserServiceImpl();
            set(service, "validator", factory.getValidator());
            set(service, "deptService", Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ISysDeptService.class }, (proxy, method, args) -> null));
            set(service, "userMapper", Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { SysUserMapper.class }, (proxy, method, args) -> {
                if ("selectUserByUserName".equals(method.getName())) return null;
                throw new AssertionError("Weak import password must not reach a database write");
            }));
            for (String password : new String[] { "123456", "passwordpassword", "importcase123456789" })
            {
                set(service, "configService", config(password));
                SysUser user = new SysUser();
                user.setUserName("importcase");
                user.setNickName("importcase");
                try { service.importUser(Collections.singletonList(user), false, "test"); fail("Weak import password was accepted"); }
                catch (ServiceException error) {
                    assertTrue(error.getMessage().contains("sys.user.initPassword"));
                    assertFalse(error.getMessage().contains(password));
                }
            }
        }
    }

    private ISysConfigService config(String password)
    {
        return (ISysConfigService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ISysConfigService.class }, (proxy, method, args) -> {
            if ("selectCaptchaEnabled".equals(method.getName())) return false;
            if ("selectConfigByKey".equals(method.getName())) return password;
            throw new AssertionError("Unexpected configuration access");
        });
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
