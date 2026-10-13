package com.elsworne.stubline.issuer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Development-only authorization-code and PKCE endpoints for a configured public client. */
public final class AuthorizationCodeFlow {
  private static final String FORM_TYPE = "application/x-www-form-urlencoded";
  private static final String JSON_TYPE = "application/json";
  private final DevTokenIssuer issuer;
  private final String clientId;
  private final Set<String> redirectUris;
  private final Set<String> webOrigins;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();

  private record PendingCode(
      String subject, String clientId, String redirectUri, String challenge, Instant expires) {}

  public AuthorizationCodeFlow(
      DevTokenIssuer issuer,
      String clientId,
      Set<String> redirectUris,
      Set<String> webOrigins,
      Clock clock) {
    if (clientId == null || clientId.isBlank() || redirectUris.isEmpty() || webOrigins.isEmpty()) {
      throw new IllegalArgumentException(
          "OAuth client, redirect URIs and web origins are required");
    }
    this.issuer = issuer;
    this.clientId = clientId;
    this.redirectUris = Set.copyOf(redirectUris);
    this.webOrigins = Set.copyOf(webOrigins);
    this.clock = clock;
  }

  public void register(HttpServer server) {
    server.createContext("/authorize", this::authorize);
    server.createContext("/token", this::token);
  }

  private void authorize(HttpExchange exchange) throws IOException {
    try {
      Map<String, String> params;
      if (exchange.getRequestMethod().equals("GET")) {
        params = parse(exchange.getRequestURI().getRawQuery());
      } else if (exchange.getRequestMethod().equals("POST") && formRequest(exchange)) {
        params =
            parse(new String(exchange.getRequestBody().readNBytes(8192), StandardCharsets.UTF_8));
      } else {
        respond(exchange, 405, "text/plain", "Method not allowed");
        return;
      }
      if (!clientId.equals(params.get("client_id"))
          || !redirectUris.contains(params.get("redirect_uri"))
          || !"code".equals(params.get("response_type"))
          || !nonblank(params.get("state"))
          || !"S256".equals(params.get("code_challenge_method"))
          || !validChallenge(params.get("code_challenge"))) {
        respond(exchange, 400, "text/plain", "Invalid authorization request");
        return;
      }
      if (exchange.getRequestMethod().equals("GET")) {
        StringBuilder form =
            new StringBuilder(
                "<!doctype html><title>Development sign-in</title><h1>Development sign-in</h1><form method=\"post\" action=\"/authorize\"><label>Buyer subject <input name=\"sub\" required></label>");
        for (String key :
            new String[] {
              "client_id",
              "redirect_uri",
              "response_type",
              "state",
              "code_challenge",
              "code_challenge_method"
            }) {
          form.append("<input type=\"hidden\" name=\"")
              .append(key)
              .append("\" value=\"")
              .append(html(params.get(key)))
              .append("\">");
        }
        respond(
            exchange,
            200,
            "text/html; charset=utf-8",
            form.append("<button>Sign in</button></form>").toString());
        return;
      }
      String subject = params.get("sub");
      if (!nonblank(subject) || subject.length() > 256) {
        respond(exchange, 400, "text/plain", "Invalid buyer subject");
        return;
      }
      byte[] bytes = new byte[32];
      random.nextBytes(bytes);
      String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      codes.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(clock.instant()));
      codes.put(
          code,
          new PendingCode(
              subject,
              clientId,
              params.get("redirect_uri"),
              params.get("code_challenge"),
              clock.instant().plusSeconds(60)));
      URI redirect = URI.create(params.get("redirect_uri"));
      String separator = redirect.getRawQuery() == null ? "?" : "&";
      exchange
          .getResponseHeaders()
          .set(
              "Location",
              redirect
                  + separator
                  + "code="
                  + encode(code)
                  + "&state="
                  + encode(params.get("state")));
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    } catch (IllegalArgumentException e) {
      respond(exchange, 400, "text/plain", "Invalid authorization request");
    }
  }

  private void token(HttpExchange exchange) throws IOException {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null) {
      if (!webOrigins.contains(origin)) {
        respond(exchange, 403, JSON_TYPE, "{\"error\":\"invalid_origin\"}");
        return;
      }
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
      exchange.getResponseHeaders().set("Vary", "Origin");
      exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
      exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
      if (exchange.getRequestMethod().equals("OPTIONS")) {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return;
      }
    }
    if (exchange.getRequestMethod().equals("GET")) {
      try {
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        String subject = query.getOrDefault("sub", "buyer-a");
        respond(exchange, 200, "text/plain", issuer.issue(subject));
      } catch (Exception e) {
        respond(exchange, 500, "text/plain", "Token issue failed");
      }
      return;
    }
    if (!exchange.getRequestMethod().equals("POST") || !formRequest(exchange)) {
      respond(exchange, 405, JSON_TYPE, "{\"error\":\"invalid_request\"}");
      return;
    }
    try {
      Map<String, String> params =
          parse(new String(exchange.getRequestBody().readNBytes(8192), StandardCharsets.UTF_8));
      if (!"authorization_code".equals(params.get("grant_type"))
          || !clientId.equals(params.get("client_id"))
          || !redirectUris.contains(params.get("redirect_uri"))
          || !validVerifier(params.get("code_verifier"))
          || !nonblank(params.get("code"))) {
        respond(exchange, 400, JSON_TYPE, "{\"error\":\"invalid_request\"}");
        return;
      }
      PendingCode pending = codes.remove(params.get("code"));
      if (pending == null
          || !pending.expires().isAfter(clock.instant())
          || !pending.clientId().equals(params.get("client_id"))
          || !pending.redirectUri().equals(params.get("redirect_uri"))
          || !pending.challenge().equals(challenge(params.get("code_verifier")))) {
        respond(exchange, 400, JSON_TYPE, "{\"error\":\"invalid_grant\"}");
        return;
      }
      String jwt = issuer.issue(pending.subject());
      respond(
          exchange,
          200,
          JSON_TYPE,
          "{\"access_token\":\"" + jwt + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
    } catch (IllegalArgumentException e) {
      respond(exchange, 400, JSON_TYPE, "{\"error\":\"invalid_request\"}");
    } catch (Exception e) {
      respond(exchange, 500, JSON_TYPE, "{\"error\":\"server_error\"}");
    }
  }

  private static boolean formRequest(HttpExchange exchange) {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    return type != null && type.split(";", 2)[0].trim().equalsIgnoreCase(FORM_TYPE);
  }

  private static Map<String, String> parse(String encoded) {
    Map<String, String> params = new HashMap<>();
    if (encoded == null) return params;
    for (String pair : encoded.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      if (params.putIfAbsent(key, value) != null)
        throw new IllegalArgumentException("duplicate parameter");
    }
    return params;
  }

  private static boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean validChallenge(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{43}");
  }

  private static boolean validVerifier(String value) {
    return value != null && value.matches("[A-Za-z0-9._~\\-]{43,128}");
  }

  private static String challenge(String verifier) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String html(String value) {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private static void respond(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
