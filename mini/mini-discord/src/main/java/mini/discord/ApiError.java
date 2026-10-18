package mini.discord;

import static mini.discord.Json.*;

import tools.jackson.databind.node.ObjectNode;

final class ApiError extends RuntimeException {
    final int status;
    final int code;
    final String oauth;

    ApiError(int status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.oauth = "";
    }

    ApiError(String oauth) {
        super(oauth);
        this.status = oauth.equals("invalid_client") ? 401 : 400;
        this.code = 0;
        this.oauth = oauth;
    }

    ObjectNode body() {
        return oauth.isEmpty() ? obj("code", code, "message", getMessage()) : obj("error", oauth);
    }

    static ApiError invalid(String field) {
        return new ApiError(400, 50035, "Invalid Form Body: " + field);
    }

    static ApiError permissions() {
        return new ApiError(403, 50013, "Missing Permissions");
    }

    static ApiError unauthorized() {
        return new ApiError(401, 0, "401: Unauthorized");
    }

    static ApiError unsupported() {
        return new ApiError(404, 0, "404: Not Found");
    }
}
