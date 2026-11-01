package com.nextturn.util;

import java.util.regex.Pattern;

/**
 * Utility for strict RFC 5322 email validation enforcing anti-abuse policies:
 * <ul>
 *     <li>Disallows the '+' character entirely to eliminate sub-addressing / alias abuse.</li>
 *     <li>Strictly validates '.' placement: no leading, trailing, or consecutive '..' dots in local or domain parts.</li>
 *     <li>Requires a valid domain with a top-level domain (TLD) of at least 2 characters.</li>
 * </ul>
 */
public final class EmailValidationUtil {

    /**
     * Strict regular expression rejecting '+' and improper '.' placement in email addresses.
     */
    public static final String STRICT_EMAIL_REGEX =
            "^[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*@[a-zA-Z0-9]+(?:-[a-zA-Z0-9]+)*(?:\\.[a-zA-Z0-9]+(?:-[a-zA-Z0-9]+)*)*\\.[a-zA-Z]{2,}$";

    private static final Pattern STRICT_EMAIL_PATTERN = Pattern.compile(STRICT_EMAIL_REGEX);

    private EmailValidationUtil() {
        // Prevent instantiation
    }

    /**
     * Checks if the given email is strictly valid.
     *
     * @param email candidate email address
     * @return {@code true} if valid and adheres to no '+' and strict '.' rules, {@code false} otherwise
     */
    public static boolean isValid(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return STRICT_EMAIL_PATTERN.matcher(email.trim()).matches();
    }
}

