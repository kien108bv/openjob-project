package com.openjob.admin.config.filter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjob.admin.adminuser.AdminUserService;
import com.openjob.admin.config.ConfigProperty;
import com.openjob.common.model.Admin;
import com.openjob.common.response.ErrorResponse;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.tomcat.util.json.JSONParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


public class CustomAuthenticationFilter extends UsernamePasswordAuthenticationFilter {
    private final AuthenticationManager authenticationManager;
    private final ConfigProperty configProperties;
    private final AdminUserService adminUserService;

    public CustomAuthenticationFilter(AuthenticationManager authenticationManager, ConfigProperty configProperties, AdminUserService adminUserService) {
        this.authenticationManager = authenticationManager;
        this.configProperties = configProperties;
        this.adminUserService = adminUserService;
    }

    @Data
    static class Credential {
        String username, password;
    }

    @SneakyThrows
    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {

        String bodyParams = getParamsFromPost(request);
        Credential credential = new ObjectMapper().readValue(bodyParams, Credential.class);


        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(credential.username, credential.password);
        return authenticationManager.authenticate(token);
    }
    private String getParamsFromPost(HttpServletRequest request) throws IOException {
        BufferedReader reader = request.getReader();
        StringBuilder sb = new StringBuilder();
        String line = reader.readLine();
        while (line != null) {
            sb.append(line + "\n");
            line = reader.readLine();
        }
        reader.close();
        String params = sb.toString();
        String[] _params = params.split("&");
        for (String param : _params) {
            System.out.println("params(POST)-->" + param);
        }
        return params;
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Authentication authentication) throws IOException {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Algorithm algorithm = Algorithm.HMAC256(configProperties.getConfigValue("secret-key").getBytes());
        String accessToken = JWT.create()
                .withSubject(userDetails.getUsername())
                .withExpiresAt(new Date(System.currentTimeMillis()
                        + Long.parseLong(configProperties.getConfigValue("access-token.expire-time"))))
                .withIssuer(request.getRequestURL().toString())
                .withClaim("role", userDetails.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList()))
                .sign(algorithm);
        String refreshToken = JWT.create()
                .withSubject(userDetails.getUsername())
                .withExpiresAt(new Date(System.currentTimeMillis()
                        + Long.parseLong(configProperties.getConfigValue("refresh-token.expire-time"))))
                .withIssuer(request.getRequestURL().toString())
                .sign(algorithm);

        Optional<Admin> admin = adminUserService.findByUsername(userDetails.getUsername());
        Map<String, String> responseDTO = new HashMap<>();
        responseDTO.put("access-token", accessToken);
        responseDTO.put("refresh-token", refreshToken);
        responseDTO.put("id", admin.get().getId());
        responseDTO.put("role", admin.get().getRole().name());

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        new ObjectMapper().writeValue(response.getOutputStream(), responseDTO);
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response, AuthenticationException failed) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setErrorCode(HttpStatus.UNAUTHORIZED.value());
        errorResponse.setErrorMessage("Bad credentials");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        new ObjectMapper().writeValue(response.getOutputStream(), errorResponse);

    }
}
