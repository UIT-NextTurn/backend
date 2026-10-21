package com.nextturn.security.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parameter annotation that injects the currently authenticated {@link com.nextturn.security.principal.AuthenticatedUser}
 * into a Spring MVC controller handler method parameter.
 *
 * <p>Example usage:
 * <pre>
 * &#64;GetMapping("/me")
 * public ResponseEntity&lt;AuthenticatedUser&gt; getCurrentUser(&#64;CurrentUser AuthenticatedUser user) {
 *     return ResponseEntity.ok(user);
 * }
 * </pre>
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
