/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.authentication;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.TestOAuth2Authorizations;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.TestRegisteredClients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ClientSecretAuthenticationProvider}.
 *
 * @author Patryk Kostrzewa
 * @author Joe Grandja
 * @author Daniel Garnier-Moiroux
 */
public class ClientSecretAuthenticationProviderTests {
	// See RFC 7636: Appendix B.  Example for the S256 code_challenge_method
	// https://tools.ietf.org/html/rfc7636#appendix-B
	private static final String S256_CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
	private static final String S256_CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	private static final String AUTHORIZATION_CODE = "code";
	private static final OAuth2TokenType AUTHORIZATION_CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

	private RegisteredClientRepository registeredClientRepository;
	private OAuth2AuthorizationService authorizationService;
	private ClientSecretAuthenticationProvider authenticationProvider;
	private PasswordEncoder passwordEncoder;

	@Before
	public void setUp() {
		this.registeredClientRepository = mock(RegisteredClientRepository.class);
		this.authorizationService = mock(OAuth2AuthorizationService.class);
		this.authenticationProvider = new ClientSecretAuthenticationProvider(
				this.registeredClientRepository, this.authorizationService);
		this.passwordEncoder = spy(new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return NoOpPasswordEncoder.getInstance().encode(rawPassword);
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return NoOpPasswordEncoder.getInstance().matches(rawPassword, encodedPassword);
			}
		});
		this.authenticationProvider.setPasswordEncoder(this.passwordEncoder);
	}

	@Test
	public void constructorWhenRegisteredClientRepositoryNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new ClientSecretAuthenticationProvider(null, this.authorizationService))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("registeredClientRepository cannot be null");
	}

	@Test
	public void constructorWhenAuthorizationServiceNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new ClientSecretAuthenticationProvider(this.registeredClientRepository, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorizationService cannot be null");
	}

	@Test
	public void setPasswordEncoderWhenNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> this.authenticationProvider.setPasswordEncoder(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("passwordEncoder cannot be null");
	}

	@Test
	public void supportsWhenTypeOAuth2ClientAuthenticationTokenThenReturnTrue() {
		assertThat(this.authenticationProvider.supports(OAuth2ClientAuthenticationToken.class)).isTrue();
	}

	@Test
	public void authenticateWhenInvalidClientIdThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId() + "-invalid", ClientAuthenticationMethod.CLIENT_SECRET_BASIC, registeredClient.getClientSecret(), null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains(OAuth2ParameterNames.CLIENT_ID);
				});
	}

	@Test
	public void authenticateWhenUnsupportedClientAuthenticationMethodThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_POST, registeredClient.getClientSecret(), null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains("authentication_method");
				});
	}

	@Test
	public void authenticateWhenClientSecretNotProvidedThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication =
				new OAuth2ClientAuthenticationToken(registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, null, null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains("credentials");
				});
	}

	@Test
	public void authenticateWhenInvalidClientSecretThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, registeredClient.getClientSecret() + "-invalid", null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains(OAuth2ParameterNames.CLIENT_SECRET);
				});
		verify(this.passwordEncoder).matches(any(), any());
	}

	@Test
	public void authenticateWhenValidCredentialsThenAuthenticated() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, registeredClient.getClientSecret(), null);
		OAuth2ClientAuthenticationToken authenticationResult =
				(OAuth2ClientAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.passwordEncoder).matches(any(), any());
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getPrincipal().toString()).isEqualTo(registeredClient.getClientId());
		assertThat(authenticationResult.getCredentials().toString()).isEqualTo(registeredClient.getClientSecret());
		assertThat(authenticationResult.getRegisteredClient()).isEqualTo(registeredClient);
	}

	@Test
	public void authenticateWhenAuthorizationCodeGrantAndValidCredentialsThenAuthenticated() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		when(this.authorizationService.findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE)))
				.thenReturn(TestOAuth2Authorizations.authorization().build());
		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, registeredClient.getClientSecret(), createAuthorizationCodeTokenParameters());
		OAuth2ClientAuthenticationToken authenticationResult =
				(OAuth2ClientAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.passwordEncoder).matches(any(), any());
		verify(this.authorizationService).findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE));
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getPrincipal().toString()).isEqualTo(registeredClient.getClientId());
		assertThat(authenticationResult.getCredentials().toString()).isEqualTo(registeredClient.getClientSecret());
		assertThat(authenticationResult.getRegisteredClient()).isEqualTo(registeredClient);
	}

	@Test
	public void authenticateWhenPkceAndInvalidCodeThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2Authorization authorization = TestOAuth2Authorizations
				.authorization(registeredClient, createPkceAuthorizationParametersS256())
				.build();
		when(this.authorizationService.findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE)))
				.thenReturn(authorization);

		Map<String, Object> parameters = createPkceTokenParameters(S256_CODE_VERIFIER);
		parameters.put(OAuth2ParameterNames.CODE, "invalid-code");

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, registeredClient.getClientSecret(), parameters);

		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT);
					assertThat(error.getDescription()).contains(OAuth2ParameterNames.CODE);
				});
	}

	@Test
	public void authenticateWhenPkceAndMissingCodeVerifierThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2Authorization authorization = TestOAuth2Authorizations
				.authorization(registeredClient, createPkceAuthorizationParametersS256())
				.build();
		when(this.authorizationService.findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE)))
				.thenReturn(authorization);

		Map<String, Object> parameters = createAuthorizationCodeTokenParameters();

		OAuth2ClientAuthenticationToken authentication =
				new OAuth2ClientAuthenticationToken(registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, registeredClient.getClientSecret(), parameters);

		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT);
					assertThat(error.getDescription()).contains(PkceParameterNames.CODE_VERIFIER);
				});
	}

	@Test
	public void authenticateWhenPkceAndValidCodeVerifierThenAuthenticated() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2Authorization authorization = TestOAuth2Authorizations
				.authorization(registeredClient, createPkceAuthorizationParametersS256())
				.build();
		when(this.authorizationService.findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE)))
				.thenReturn(authorization);

		Map<String, Object> parameters = createPkceTokenParameters(S256_CODE_VERIFIER);

		OAuth2ClientAuthenticationToken authentication =
				new OAuth2ClientAuthenticationToken(registeredClient.getClientId(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, registeredClient.getClientSecret(), parameters);
		OAuth2ClientAuthenticationToken authenticationResult =
				(OAuth2ClientAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.passwordEncoder).matches(any(), any());
		verify(this.authorizationService).findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE));
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getPrincipal().toString()).isEqualTo(registeredClient.getClientId());
		assertThat(authenticationResult.getCredentials().toString()).isEqualTo(registeredClient.getClientSecret());
		assertThat(authenticationResult.getRegisteredClient()).isEqualTo(registeredClient);
	}

	private static Map<String, Object> createAuthorizationCodeTokenParameters() {
		Map<String, Object> parameters = new HashMap<>();
		parameters.put(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
		parameters.put(OAuth2ParameterNames.CODE, AUTHORIZATION_CODE);
		return parameters;
	}

	private static Map<String, Object> createPkceTokenParameters(String codeVerifier) {
		Map<String, Object> parameters = createAuthorizationCodeTokenParameters();
		parameters.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
		return parameters;
	}

	private static Map<String, Object> createPkceAuthorizationParametersS256() {
		Map<String, Object> parameters = new HashMap<>();
		parameters.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
		parameters.put(PkceParameterNames.CODE_CHALLENGE, S256_CODE_CHALLENGE);
		return parameters;
	}

}
