package com.nextturn.infrastructure.security.principal;

import com.nextturn.infrastructure.security.annotation.CurrentUser;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Spring MVC {@link HandlerMethodArgumentResolver} that automatically resolves and injects
 * the {@link AuthenticatedUser} into controller handler methods annotated with {@link CurrentUser}.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final SecurityContextHelper securityContextHelper;

    public CurrentUserArgumentResolver(SecurityContextHelper securityContextHelper) {
        this.securityContextHelper = securityContextHelper;
    }

    public CurrentUserArgumentResolver() {
        this(new SecurityContextHelper());
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        boolean hasAnnotation = parameter.hasParameterAnnotation(CurrentUser.class);
        boolean isUserType = AuthenticatedUser.class.isAssignableFrom(parameter.getParameterType());
        return (hasAnnotation && isUserType) || isUserType;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        return securityContextHelper.getCurrentUser().orElse(null);
    }
}
