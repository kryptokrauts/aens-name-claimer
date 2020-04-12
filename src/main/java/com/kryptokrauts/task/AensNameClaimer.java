package com.kryptokrauts.task;

import com.kryptokrauts.aeternity.sdk.constants.AENS;
import com.kryptokrauts.aeternity.sdk.service.account.domain.AccountResult;
import com.kryptokrauts.aeternity.sdk.service.aeternal.domain.ActiveNameAuctionResult;
import com.kryptokrauts.aeternity.sdk.service.aeternal.domain.ActiveNameAuctionsResult;
import com.kryptokrauts.aeternity.sdk.service.aeternal.domain.ActiveNameResult;
import com.kryptokrauts.aeternity.sdk.service.aeternity.AeternityServiceConfiguration;
import com.kryptokrauts.aeternity.sdk.service.aeternity.impl.AeternityService;
import com.kryptokrauts.aeternity.sdk.service.name.domain.NameIdResult;
import com.kryptokrauts.aeternity.sdk.service.transaction.domain.PostTransactionResult;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NameClaimTransactionModel;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NamePreclaimTransactionModel;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NameUpdateTransactionModel;
import com.kryptokrauts.aeternity.sdk.util.CryptoUtils;
import com.kryptokrauts.config.NameConfig;
import com.kryptokrauts.config.NameConfig.NameEntry;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AensNameClaimer {

  @Autowired private AeternityService aeternityService;
  @Autowired private AeternityServiceConfiguration aeternityServiceConfiguration;

  @Autowired private NameConfig nameConfig;

  private static final BigInteger MAX_TTL = BigInteger.valueOf(50000);

  @Scheduled(fixedDelay = 3600000)
  public void scheduleFixedDelayTask() throws InterruptedException {
    for (NameEntry nameEntry : nameConfig.getWatchlist()) {
      boolean active = aeternityService.aeternal.blockingIsNameAuctionActive(nameEntry.getName());
      Optional<ActiveNameAuctionResult> activeNameAuctionResult =
          this.getActiveNameAuctionResult(nameEntry.getName());
      if (active) {
        log.info("{}: found active auction", nameEntry.getName());
        if (aeternityServiceConfiguration
            .getBaseKeyPair()
            .getPublicKey()
            .equals(activeNameAuctionResult.get().getWinningBidder())) {
          log.info("{}: we are currently the highest bidder", nameEntry.getName());
        } else {
          log.info(
              "{}: we have been outbidden and need to perform a new claim", nameEntry.getName());
          log.info(
              "{}: current highest bid: {}",
              nameEntry.getName(),
              activeNameAuctionResult.get().getWinningBid());
          if (nameEntry
                  .getMaxBid()
                  .compareTo(AENS.getNextNameFee(activeNameAuctionResult.get().getWinningBid()))
              == -1) {
            log.info("{}: maxBid value exceeded. skipping the claim", nameEntry.getName());
          } else {
            performClaim(nameEntry.getName(), activeNameAuctionResult.get().getWinningBid());
          }
        }
      } else {
        NameIdResult nameIdResult = aeternityService.names.blockingGetNameId(nameEntry.getName());
        if (nameIdResult.getRootErrorMessage() != null) {
          log.info("{}: name was never claimed before", nameEntry.getName());
          initialActions(nameEntry.getName());
        } else {
          log.info("{}: name already claimed: {}", nameEntry.getName(), nameIdResult);
          ActiveNameResult activeNameResult =
              aeternityService.aeternal.blockingSearchName(nameEntry.getName())
                  .getActiveNameResults().stream()
                  .filter(it -> it.getName().equalsIgnoreCase(nameEntry.getName()))
                  .findFirst()
                  .get();
          /** only perform update if we are the owner and if the name should be updated */
          if (activeNameResult
                  .getOwner()
                  .equals(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey())
              && nameEntry.isUpdate()) {
            performUpdate(nameIdResult.getId(), nameConfig.getPointers());
            nameIdResult = aeternityService.names.blockingGetNameId(nameEntry.getName());
            log.info("{}: updated: {}", nameEntry.getName(), nameIdResult);
          } else {
            log.info("{}: skip update", nameEntry.getName());
          }
        }
      }
    }
    log.info("account: {}", aeternityService.accounts.blockingGetAccount(Optional.empty()));
  }

  /**
   * @param name the name to claim
   * @param currentFee the current nameFee of the name
   * @throws InterruptedException
   */
  private void performClaim(final String name, final BigInteger currentFee)
      throws InterruptedException {
    BigInteger fee = AENS.getNextNameFee(currentFee);
    NameClaimTransactionModel nameClaimTransactionModel =
        NameClaimTransactionModel.builder()
            .name(name)
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
    log.info("successfully claimed: {}", name);
  }

  /**
   * @param nameId the nameId of the name to update
   * @param pointers the list of pointers (allowed: account, channel, contract, oracle)
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
            .pointers(pointers)
            .build();
    log.info("NameUpdateTx-model: {}", nameUpdateTransactionModel);
    PostTransactionResult postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(nameUpdateTransactionModel);
    log.info("NameUpdateTx-hash: {}", postTransactionResult.getTxHash());
  }

  /** performs a preclaim and claim for the respective name */
  private void initialActions(String name) throws InterruptedException {
    log.info("performing preclaim and claim for {}", name);
    BigInteger salt = CryptoUtils.generateNamespaceSalt();
    NamePreclaimTransactionModel namePreclaimTransactionModel =
        NamePreclaimTransactionModel.builder()
            .name(name)
            .accountId(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey())
            .salt(salt)
            .nonce(getNextNonce())
            .ttl(BigInteger.ZERO)
            .build();
    log.info("NamePreclaimTx-model: {}", namePreclaimTransactionModel);
    PostTransactionResult postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(namePreclaimTransactionModel);
    log.info("NamePreclaimTx-hash: {}", postTransactionResult.getTxHash());
    NameClaimTransactionModel nameClaimTransactionModel =
        NameClaimTransactionModel.builder()
            .name(name)
            .nameSalt(salt)
            .accountId(aeternityServiceConfiguration.getBaseKeyPair().getPublicKey())
            .nonce(getNextNonce())
            .ttl(BigInteger.ZERO)
            .build();
    log.info("NameClaimTx-model: {}", nameClaimTransactionModel);
    postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(nameClaimTransactionModel);
    log.info("NameClaimTx-hash: {}", postTransactionResult.getTxHash());
    log.info("successfully claimed: {}", name);
  }

  private Optional<ActiveNameAuctionResult> getActiveNameAuctionResult(String name) {
    ActiveNameAuctionsResult activeNameAuctionsResult =
        aeternityService.aeternal.blockingGetActiveNameAuctions();
    return activeNameAuctionsResult.getActiveNameAuctionResults().stream()
        .filter(auction -> auction.getName().equalsIgnoreCase(name))
        .findFirst();
  }

  private BigInteger getNextNonce() {
    AccountResult accountResult = aeternityService.accounts.blockingGetAccount(Optional.empty());
    if (accountResult.getRootErrorMessage() != null) {
      return BigInteger.ONE;
    }
    return accountResult.getNonce().add(BigInteger.ONE);
  }
}
