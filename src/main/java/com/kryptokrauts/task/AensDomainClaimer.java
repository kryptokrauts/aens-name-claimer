package com.kryptokrauts.task;

import com.kryptokrauts.aeternity.sdk.constants.AENS;
import com.kryptokrauts.aeternity.sdk.service.account.domain.AccountResult;
import com.kryptokrauts.aeternity.sdk.service.aeternal.domain.ActiveAuctionResult;
import com.kryptokrauts.aeternity.sdk.service.aeternal.domain.ActiveAuctionsResult;
import com.kryptokrauts.aeternity.sdk.service.aeternity.AeternityServiceConfiguration;
import com.kryptokrauts.aeternity.sdk.service.aeternity.impl.AeternityService;
import com.kryptokrauts.aeternity.sdk.service.name.domain.NameIdResult;
import com.kryptokrauts.aeternity.sdk.service.transaction.domain.PostTransactionResult;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NameClaimTransactionModel;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NamePreclaimTransactionModel;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NameUpdateTransactionModel;
import com.kryptokrauts.aeternity.sdk.util.CryptoUtils;
import com.kryptokrauts.config.DomainConfig;
import com.kryptokrauts.config.DomainConfig.NameEntry;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AensDomainClaimer {

  @Autowired private AeternityService aeternityService;
  @Autowired private AeternityServiceConfiguration aeternityServiceConfiguration;

  @Autowired private DomainConfig domainConfig;

  private static final BigInteger MAX_TTL = BigInteger.valueOf(50000);

  @Scheduled(fixedDelay = 3600000)
  public void scheduleFixedDelayTask() throws InterruptedException {
    for (NameEntry nameEntry : domainConfig.getWatchlist()) {
      boolean active = aeternityService.aeternal.blockingIsAuctionActive(nameEntry.getDomain());
      Optional<ActiveAuctionResult> activeAuctionResult =
          this.getActiveAuctionResult(nameEntry.getDomain());
      if (active) {
        log.info("{}: found active auction", nameEntry.getDomain());
        if (aeternityServiceConfiguration
            .getBaseKeyPair()
            .getPublicKey()
            .equals(activeAuctionResult.get().getWinningBidder())) {
          log.info("{}: we are currently the highest bidder", nameEntry.getDomain());
        } else {
          log.info(
              "{}: we have been outbidden and need to perform a new claim", nameEntry.getDomain());
          log.info(
              "{}: current highest bid: {}",
              nameEntry.getDomain(),
              activeAuctionResult.get().getWinningBid());
          if (nameEntry
                  .getMaxBid()
                  .compareTo(AENS.getNextNameFee(activeAuctionResult.get().getWinningBid()))
              == -1) {
            log.info("{}: maxBid value exceeded. skipping the claim", nameEntry.getDomain());
          } else {
            performClaim(nameEntry.getDomain(), activeAuctionResult.get().getWinningBid());
          }
        }
      } else {
        NameIdResult nameIdResult = aeternityService.names.blockingGetNameId(nameEntry.getDomain());
        if (nameIdResult.getRootErrorMessage() != null) {
          log.info("{}: domain was never claimed before", nameEntry.getDomain());
          initialActions(nameEntry.getDomain());
        } else {
          log.info("{}: domain already claimed: {}", nameEntry.getDomain(), nameIdResult);
          // TODO check whether we are the owner of the domain
          //  (functionality currently missing in the SDK)
          //  for now we assume that we are the owner
          if (nameEntry.isUpdate()) {
            performUpdate(
                nameIdResult.getId(),
                Arrays.asList(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey()));
            nameIdResult = aeternityService.names.blockingGetNameId(nameEntry.getDomain());
            log.info("{}: updated: {}", nameEntry.getDomain(), nameIdResult);
          } else {
            log.info("{}: skip update", nameEntry.getDomain());
          }
        }
      }
    }
    log.info("account: {}", aeternityService.accounts.blockingGetAccount(Optional.empty()));
  }

  /**
   * @param domain the domain to claim
   * @param currentFee the current nameFee of the domain
   * @throws InterruptedException
   */
  private void performClaim(final String domain, final BigInteger currentFee)
      throws InterruptedException {
    BigInteger fee = AENS.getNextNameFee(currentFee);
    NameClaimTransactionModel nameClaimTransactionModel =
        NameClaimTransactionModel.builder()
            .name(domain)
            .nameSalt(BigInteger.ZERO)
            .accountId(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey())
            .nonce(getNextNonce())
            .nameFee(fee)
            .ttl(BigInteger.ZERO)
            .build();
    log.info("NameClaimTx-model: {}", nameClaimTransactionModel);
    PostTransactionResult postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(nameClaimTransactionModel);
    log.info("NameClaimTx-hash: {}", postTransactionResult.getTxHash());
    waitForTxMined(postTransactionResult.getTxHash());
    log.info("successfully claimed: {}", domain);
  }

  /**
   * @param nameId the nameId of the domain entry to update
   * @param pointers the list of adresses to point to (allowed: account, channel, contract, oracle)
   * @throws InterruptedException
   */
  private void performUpdate(final String nameId, final List<String> pointers)
      throws InterruptedException {
    NameUpdateTransactionModel nameUpdateTransactionModel =
        NameUpdateTransactionModel.builder()
            .accountId(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey())
            .nameId(nameId)
            .nonce(getNextNonce())
            .ttl(BigInteger.ZERO)
            .clientTtl(BigInteger.ZERO)
            .nameTtl(MAX_TTL)
            .pointerAddresses(pointers)
            .build();
    log.info("NameUpdateTx-model: {}", nameUpdateTransactionModel);
    PostTransactionResult postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(nameUpdateTransactionModel);
    log.info("NameUpdateTx-hash: {}", postTransactionResult.getTxHash());
    waitForTxMined(postTransactionResult.getTxHash());
  }

  /** performs a preclaim and claim for the respective domain */
  private void initialActions(String domain) throws InterruptedException {
    log.info("performing preclaim and claim for {}", domain);
    BigInteger salt = CryptoUtils.generateNamespaceSalt();
    NamePreclaimTransactionModel namePreclaimTransactionModel =
        NamePreclaimTransactionModel.builder()
            .name(domain)
            .accountId(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey())
            .salt(salt)
            .nonce(getNextNonce())
            .ttl(BigInteger.ZERO)
            .build();
    log.info("NamePreclaimTx-model: {}", namePreclaimTransactionModel);
    PostTransactionResult postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(namePreclaimTransactionModel);
    log.info("NamePreclaimTx-hash: {}", postTransactionResult.getTxHash());
    waitForTxMined(postTransactionResult.getTxHash());
    NameClaimTransactionModel nameClaimTransactionModel =
        NameClaimTransactionModel.builder()
            .name(domain)
            .nameSalt(salt)
            .accountId(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey())
            .nonce(getNextNonce())
            .ttl(BigInteger.ZERO)
            .build();
    log.info("NameClaimTx-model: {}", nameClaimTransactionModel);
    postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(nameClaimTransactionModel);
    log.info("NameClaimTx-hash: {}", postTransactionResult.getTxHash());
    waitForTxMined(postTransactionResult.getTxHash());
    log.info("successfully claimed: {}", domain);
  }

  private Optional<ActiveAuctionResult> getActiveAuctionResult(String domain) {
    ActiveAuctionsResult activeAuctionsResult =
        aeternityService.aeternal.blockingGetNameAuctionsActive();
    return activeAuctionsResult.getActiveAuctionResults().stream()
        .filter(auction -> auction.getName().equals(domain))
        .findFirst();
  }

  private BigInteger getNextNonce() {
    AccountResult accountResult = aeternityService.accounts.blockingGetAccount(Optional.empty());
    if (accountResult.getRootErrorMessage() != null) {
      return BigInteger.ONE;
    }
    return accountResult.getNonce().add(BigInteger.ONE);
  }

  /**
   * checks every 5 seconds whether the transaction is mined or not
   *
   * @param txHash
   * @throws InterruptedException
   */
  private void waitForTxMined(final String txHash) throws InterruptedException {
    int blockHeight = -1;
    while (blockHeight == -1) {
      log.info("waiting for tx to be mined ...");
      blockHeight =
          aeternityService.info.blockingGetTransactionByHash(txHash).getBlockHeight().intValue();
      Thread.sleep(5000);
    }
  }
}
