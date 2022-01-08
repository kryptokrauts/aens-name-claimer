package com.kryptokrauts.config;

import com.kryptokrauts.aeternity.sdk.constants.Network;
import com.kryptokrauts.aeternity.sdk.domain.secret.KeyPair;
import com.kryptokrauts.aeternity.sdk.exception.AException;
import com.kryptokrauts.aeternity.sdk.service.aeternity.AeternityServiceConfiguration;
import com.kryptokrauts.aeternity.sdk.service.aeternity.AeternityServiceFactory;
import com.kryptokrauts.aeternity.sdk.service.aeternity.impl.AeternityService;
import com.kryptokrauts.aeternity.sdk.service.keypair.KeyPairService;
import com.kryptokrauts.aeternity.sdk.service.keypair.KeyPairServiceFactory;
import com.kryptokrauts.aeternity.sdk.service.keystore.KeystoreService;
import com.kryptokrauts.aeternity.sdk.service.keystore.KeystoreServiceFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {
  @Value("${node.url:https://testnet.aeternity.io}")
  String nodeUrl;

  @Value("${compiler.url:https://compiler.aeternity.io}")
  String compilerBaseUrl;

  @Value("${mdw.url:https://testnet.aeternity.io/mdw}")
  String mdwBaseUrl;

  @Value("${network:TESTNET}")
  Network network;

  @Value("${wallet.path}")
  String walletPath;

  @Bean
  public AeternityServiceConfiguration aeternityConfiguration() throws AException, IOException {
    return AeternityServiceConfiguration.configure()
        .baseUrl(nodeUrl)
        .compilerBaseUrl(compilerBaseUrl)
        .mdwBaseUrl(mdwBaseUrl)
        .network(network)
        .keyPair(keyPair())
        .compile();
  }

  @Bean
  public AeternityService aeternityService() throws AException, IOException {
    return new AeternityServiceFactory().getService(aeternityConfiguration());
  }

  private KeyPair keyPair() throws AException, IOException {
    String walletPassword = System.getProperty("walletPassword");
    if (walletPassword == null) {
      throw new IllegalArgumentException("walletPassword is missing");
    }
    String walletJson = FileUtils.readFileToString(new File(walletPath), StandardCharsets.UTF_8);
    KeystoreService keystoreService = new KeystoreServiceFactory().getService();
    KeyPairService keyPairService = new KeyPairServiceFactory().getService();
    String privateKey = keystoreService.recoverEncodedPrivateKey(walletJson, walletPassword);
    KeyPair keyPair = keyPairService.recoverKeyPair(privateKey);
    return keyPair;
  }
}
