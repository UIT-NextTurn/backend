package com.nextturn.constant;

/**
 * Constants for OAuth2 / OIDC protocol parameters, grant types, and token payload fields
 * (*các hằng số giao thức OAuth2, loại cấp quyền và trường dữ liệu token*).
 */
public final class OAuth2Constant {

    private OAuth2Constant() {
        // Prevent instantiation
    }

    // Standard OAuth2 Grant Types
    public static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    public static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
    public static final String GRANT_TYPE_PASSWORD = "password";

    // Request Parameters
    public static final String PARAM_GRANT_TYPE = "grant_type";
    public static final String PARAM_CLIENT_ID = "client_id";
    public static final String PARAM_CLIENT_SECRET = "client_secret";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_REDIRECT_URI = "redirect_uri";
    public static final String PARAM_REFRESH_TOKEN = "refresh_token";
    public static final String PARAM_RESPONSE_TYPE = "response_type";
    public static final String PARAM_SCOPE = "scope";
    public static final String PARAM_ORGANIZATION = "organization";
    public static final String PARAM_TENANT_CODE = "tenant_code";

    // Response Types & Scopes
    public static final String RESPONSE_TYPE_CODE = "code";
    public static final String SCOPE_OPENID = "openid";
    public static final String SCOPE_OPENID_ORGANIZATION = "openid organization";

    // Token Response JSON Fields
    public static final String FIELD_ACCESS_TOKEN = "access_token";
    public static final String FIELD_REFRESH_TOKEN = "refresh_token";
    public static final String FIELD_ID_TOKEN = "id_token";
    public static final String FIELD_EXPIRES_IN = "expires_in";
    public static final String FIELD_REFRESH_EXPIRES_IN = "refresh_expires_in";
    public static final String FIELD_TOKEN_TYPE = "token_type";
    public static final String TOKEN_TYPE_BEARER = "Bearer";

    // Keycloak User & Identity Fields
    public static final String FIELD_ID = "id";
    public static final String FIELD_SUB = "sub";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_USERNAME = "username";
    public static final String FIELD_FIRST_NAME = "firstName";
    public static final String FIELD_ENABLED = "enabled";
    public static final String FIELD_EMAIL_VERIFIED = "emailVerified";
}
