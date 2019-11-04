package com.kryptokrauts.config;

import com.kryptokrauts.aeternity.sdk.constants.Network;
import com.kryptokrauts.aeternity.sdk.constants.VirtualMachine;
import com.kryptokrauts.aeternity.sdk.domain.secret.impl.BaseKeyPair;
import com.kryptokrauts.aeternity.sdk.exception.AException;
import com.kryptokrauts.aeternity.sdk.service.aeternity.AeternityServiceConfiguration;
import com.kryptokrauts.aeternity.sdk.service.aeternity.AeternityServiceFactory;
import com.kryptokrauts.aeternity.sdk.service.aeternity.impl.AeternityService;
import com.kryptokrauts.aeternity.sdk.service.keypair.KeyPairService;
import com.kryptokrauts.aeternity.sdk.service.keypair.KeyPairServiceFactory;
import com.kryptokrauts.aeternity.sdk.service.wallet.WalletService;
import com.kryptokrauts.aeternity.sdk.service.wallet.WalletServiceFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {
  @Value("${node.url:https://sdk-testnet.aepps.com}")
  String nodeUrl;

  @Value("${compiler.url:https://compiler.aepps.com}")
  String compilerBaseUrl;

  @Value("${middleware.url:https://testnet.aeternal.io}")
  String aeternalBaseUrl;

  @Value("${network:TESTNET}")
  Network network;

  @Value("${vm:FATE}")
  VirtualMachine targetVM;

  @Value("${wallet.path}")
  String walletPath;

  @Bean
  public AeternityServiceConfiguration aeternityConfiguration() throws AException, IOException {
    return AeternityServiceConfiguration.configure()
        .baseUrl(nodeUrl)
        .compilerBaseUrl(compilerBaseUrl)
        .aeternalBaseUrl(aeternalBaseUrl)
        .network(network)
        .targetVM(targetVM)
        .baseKeyPair(baseKeyPair())
        .compile();
  }

  @Bean
  public AeternityService aeternityService() throws AException, IOException {
    return new AeternityServiceFactory().getService(aeternityConfiguration());
  }

  private BaseKeyPair baseKeyPair() throws AException, IOException {
    String walletPassword = System.getProperty("walletPassword");
    if (walletPassword == null) {
      throw new IllegalArgumentException("walletPassword is missing");
    }
    String walletJson = FileUtils.readFileToString(new File(walletPath), StandardCharsets.UTF_8);
    WalletService walletService = new WalletServiceFactory().getService();
    KeyPairService keyPairService = new KeyPairServiceFactory().getService();
    byte[] privateKey = walletService.recoverPrivateKeyFromKeystore(walletJson, walletPassword);
    BaseKeyPair keyPair = keyPairService.generateBaseKeyPairFromSecret(Hex.toHexString(privateKey));
    return keyPair;
  }
}
