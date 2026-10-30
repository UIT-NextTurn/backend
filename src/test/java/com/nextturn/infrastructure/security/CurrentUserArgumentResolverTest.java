package com.nextturn.infrastructure.security;

import com.nextturn.enums.UserRole;
import com.nextturn.infrastructure.security.annotation.CurrentUser;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class CurrentUserArgumentResolverTest {

    private SecurityContextHelper securityContextHelper;
    private CurrentUserArgumentResolver resolver;

    @BeforeEach
    void setUp() {
        securityContextHelper = Mockito.mock(SecurityContextHelper.class);
        resolver = new CurrentUserArgumentResolver(securityContextHelper);
    }

    @Test
    @DisplayName("supportsParameter should return true for @CurrentUser AuthenticatedUser")
    void shouldSupportAnnotatedAuthenticatedUser() throws NoSuchMethodException {
        Method method = SampleController.class.getMethod("withAnnotation", AuthenticatedUser.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(parameter)).isTrue();
    }

    @Test
    @DisplayName("supportsParameter should return true for bare AuthenticatedUser")
    void shouldSupportBareAuthenticatedUser() throws NoSuchMethodException {
        Method method = SampleController.class.getMethod("withoutAnnotation", AuthenticatedUser.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(parameter)).isTrue();
    }

    @Test
    @DisplayName("supportsParameter should return false for unsupported types")
    void shouldNotSupportOtherTypes() throws NoSuchMethodException {
        Method method = SampleController.class.getMethod("unsupportedType", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(parameter)).isFalse();
    }

    @Test
    @DisplayName("resolveArgument should return active AuthenticatedUser when present")
    void shouldResolveAuthenticatedUser() throws NoSuchMethodException {
        AuthenticatedUser mockUser = new AuthenticatedUser(
                "user-1",
                "test@nextturn.com",
                "tenant-1",
                Set.of(UserRole.TENANT_ADMIN)
        );
        when(securityContextHelper.getCurrentUser()).thenReturn(Optional.of(mockUser));

        Method method = SampleController.class.getMethod("withAnnotation", AuthenticatedUser.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        Object result = resolver.resolveArgument(parameter, null, null, null);

        assertThat(result).isEqualTo(mockUser);
    }

    @Test
    @DisplayName("resolveArgument should return null when no user is in context")
    void shouldReturnNullWhenNoUserInContext() throws NoSuchMethodException {
        when(securityContextHelper.getCurrentUser()).thenReturn(Optional.empty());

        Method method = SampleController.class.getMethod("withAnnotation", AuthenticatedUser.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        Object result = resolver.resolveArgument(parameter, null, null, null);

        assertThat(result).isNull();
    }

    @SuppressWarnings("unused")
    private static class SampleController {
        public void withAnnotation(@CurrentUser AuthenticatedUser user) {}
        public void withoutAnnotation(AuthenticatedUser user) {}
        public void unsupportedType(String param) {}
    }
}
