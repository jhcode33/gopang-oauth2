package com.auth.demo.service;

import com.auth.demo.persistence.model.Authorization;
import com.auth.demo.persistence.repository.AuthorizationRepository;
import com.auth.demo.security.CustomUserPrincipal;
import com.auth.demo.security.CustomUserPrincipalMixin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 *
 * @author globaliware
 */
@Component
public class JpaOAuth2AuthorizationService implements OAuth2AuthorizationService{
    
    private final AuthorizationRepository authorizationRepository;
    private final RegisteredClientRepository registeredClientRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public JpaOAuth2AuthorizationService(AuthorizationRepository authorizationRepository, RegisteredClientRepository registeredClientRepository) {
        this.authorizationRepository = authorizationRepository;
        this.registeredClientRepository = registeredClientRepository;
        ClassLoader classLoader = JpaOAuth2AuthorizationService.class.getClassLoader();
        List<Module> securityModules = SecurityJackson2Modules.getModules(classLoader);
        this.objectMapper.registerModules(securityModules);
        this.objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
        this.objectMapper.addMixIn(CustomUserPrincipal.class, CustomUserPrincipalMixin.class);
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        this.authorizationRepository.save(toEntity(authorization));
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        this.authorizationRepository.deleteById(authorization.getId());
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return this.authorizationRepository.findById(id).map(this::toObject).orElse(null);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        Optional<Authorization> result;
            if (tokenType == null) {
                    result = this.authorizationRepository.findByStateOrAuthorizationCodeValueOrAccessTokenValueOrRefreshTokenValue(token);
            } else if (OAuth2ParameterNames.STATE.equals(tokenType.getValue())) {
                    result = this.authorizationRepository.findByState(token);
            } else if (OAuth2ParameterNames.CODE.equals(tokenType.getValue())) {
                    result = this.authorizationRepository.findByAuthorizationCodeValue(token);
            } else if (OAuth2ParameterNames.ACCESS_TOKEN.equals(tokenType.getValue())) {
                    result = this.authorizationRepository.findByAccessTokenValue(token);
            } else if (OAuth2ParameterNames.REFRESH_TOKEN.equals(tokenType.getValue())) {
                    result = this.authorizationRepository.findByRefreshTokenValue(token);
            } else {
                    result = Optional.empty();
            }
        return result.map(this::toObject).orElse(null);
    }
    
    private OAuth2Authorization toObject(Authorization entity) {
        RegisteredClient registeredClient = this.registeredClientRepository.findById(entity.getRegisteredClientId());
        if (registeredClient == null) {
			throw new DataRetrievalFailureException(
					"The RegisteredClient with id '" + entity.getRegisteredClientId() + "' was not found in the RegisteredClientRepository.");
	}
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient)
				.id(entity.getId())
				.principalName(entity.getPrincipalName())
				.authorizationGrantType(resolveAuthorizationGrantType(entity.getAuthorizationGrantType()))
				.attributes(attributes -> attributes.putAll(parseMap(entity.getAttributes())));
        
        if (entity.getState() != null) {
            builder.attribute(OAuth2ParameterNames.STATE, entity.getState());
	}
        
        if (entity.getAuthorizationCodeValue() != null) {
            OAuth2AuthorizationCode authorizationCode = new OAuth2AuthorizationCode(
                                entity.getAuthorizationCodeValue(),
                                entity.getAuthorizationCodeIssuedAt(),
                                entity.getAuthorizationCodeExpiresAt());
            builder.token(authorizationCode, metadata -> metadata.putAll(parseMap(entity.getAuthorizationCodeMetadata())));
        }
        
        if (entity.getAccessTokenValue() != null) {
            OAuth2AccessToken accessToken = new OAuth2AccessToken(
                                OAuth2AccessToken.TokenType.BEARER,
                                entity.getAccessTokenValue(),
                                entity.getAccessTokenIssuedAt(),
                                entity.getAccessTokenExpiresAt(),
                                StringUtils.commaDelimitedListToSet(entity.getAccessTokenScopes()));
            builder.token(accessToken, metadata -> metadata.putAll(parseMap(entity.getAccessTokenMetadata())));
        }
        
        if (entity.getRefreshTokenValue() != null) {
            OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                                entity.getRefreshTokenValue(),
                                entity.getRefreshTokenIssuedAt(),
                                entity.getRefreshTokenExpiresAt());
            builder.token(refreshToken, metadata -> metadata.putAll(parseMap(entity.getRefreshTokenMetadata())));
        }
        
        if (entity.getOidcIdTokenValue() != null) {
            OidcIdToken idToken = new OidcIdToken(
                                entity.getOidcIdTokenValue(),
                                entity.getOidcIdTokenIssuedAt(),
                                entity.getOidcIdTokenExpiresAt(),
                                parseMap(entity.getOidcIdTokenClaims()));
            builder.token(idToken, metadata -> metadata.putAll(parseMap(entity.getOidcIdTokenMetadata())));
        }
        return builder.build();
    }
    
    private Authorization toEntity(OAuth2Authorization authorization) {
        Authorization entity = new Authorization();
        entity.setId(authorization.getId());
        entity.setRegisteredClientId(authorization.getRegisteredClientId());
        entity.setPrincipalName(authorization.getPrincipalName());
        entity.setAuthorizationGrantType(authorization.getAuthorizationGrantType().getValue());
        entity.setAttributes(writeMap(authorization.getAttributes()));
        entity.setState(authorization.getAttribute(OAuth2ParameterNames.STATE));
        
        OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode = authorization.getToken(OAuth2AuthorizationCode.class);
		setTokenValues(
				authorizationCode,
				entity::setAuthorizationCodeValue,
				entity::setAuthorizationCodeIssuedAt,
				entity::setAuthorizationCodeExpiresAt,
				entity::setAuthorizationCodeMetadata
		);
                
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getToken(OAuth2AccessToken.class);
		setTokenValues(
				accessToken,
				entity::setAccessTokenValue,
				entity::setAccessTokenIssuedAt,
				entity::setAccessTokenExpiresAt,
				entity::setAccessTokenMetadata
		);        
                
        if (accessToken != null && accessToken.getToken().getScopes() != null) {
			entity.setAccessTokenScopes(StringUtils.collectionToDelimitedString(accessToken.getToken().getScopes(), ","));
		}        
        
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getToken(OAuth2RefreshToken.class);
		setTokenValues(
				refreshToken,
				entity::setRefreshTokenValue,
				entity::setRefreshTokenIssuedAt,
				entity::setRefreshTokenExpiresAt,
				entity::setRefreshTokenMetadata
		);

		OAuth2Authorization.Token<OidcIdToken> oidcIdToken = authorization.getToken(OidcIdToken.class);
		setTokenValues(
				oidcIdToken,
				entity::setOidcIdTokenValue,
				entity::setOidcIdTokenIssuedAt,
				entity::setOidcIdTokenExpiresAt,
				entity::setOidcIdTokenMetadata
		);
		if (oidcIdToken != null) {
			entity.setOidcIdTokenClaims(writeMap(oidcIdToken.getClaims()));
		}

	return entity;
        
    }
    
    private void setTokenValues(OAuth2Authorization.Token<?> token, Consumer<String> tokenValueConsumer, Consumer<Instant> issuedAtConsumer, Consumer<Instant> expiresAtConsumer, Consumer<String> metadataConsumer) {
        if (token != null) {
                OAuth2Token oAuth2Token = token.getToken();
                tokenValueConsumer.accept(oAuth2Token.getTokenValue());
                issuedAtConsumer.accept(oAuth2Token.getIssuedAt());
                expiresAtConsumer.accept(oAuth2Token.getExpiresAt());
                metadataConsumer.accept(writeMap(token.getMetadata()));
        }
    }
    
    private Map<String, Object> parseMap(String data) {
        try {
            System.out.println(data);
            return this.objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }
    
    private String writeMap(Map<String, Object> metadata) {
        try {
            return this.objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }
    
    private static AuthorizationGrantType resolveAuthorizationGrantType(String authorizationGrantType) {
        if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(authorizationGrantType)) {
                return AuthorizationGrantType.AUTHORIZATION_CODE;
        } else if (AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(authorizationGrantType)) {
                return AuthorizationGrantType.CLIENT_CREDENTIALS;
        } else if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(authorizationGrantType)) {
                return AuthorizationGrantType.REFRESH_TOKEN;
        }
        return new AuthorizationGrantType(authorizationGrantType); 
    }
    
}
