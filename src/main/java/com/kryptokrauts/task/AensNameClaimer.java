package com.kryptokrauts.task;

import com.kryptokrauts.aeternity.sdk.constants.AENS;
import com.kryptokrauts.aeternity.sdk.service.account.domain.AccountResult;
import com.kryptokrauts.aeternity.sdk.service.aeternity.AeternityServiceConfiguration;
import com.kryptokrauts.aeternity.sdk.service.aeternity.impl.AeternityService;
import com.kryptokrauts.aeternity.sdk.service.mdw.domain.NameAuctionResult;
import com.kryptokrauts.aeternity.sdk.service.name.domain.NameEntryResult;
import com.kryptokrauts.aeternity.sdk.service.transaction.domain.PostTransactionResult;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NameClaimTransactionModel;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NamePreclaimTransactionModel;
import com.kryptokrauts.aeternity.sdk.service.transaction.type.model.NameUpdateTransactionModel;
import com.kryptokrauts.aeternity.sdk.util.CryptoUtils;
import com.kryptokrauts.config.NameConfig;
import com.kryptokrauts.config.NameConfig.NameEntry;
import java.math.BigInteger;
import java.util.Map;
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

  private static final BigInteger MAX_TTL = BigInteger.valueOf(180000);

  @Scheduled(fixedDelay = 3600000)
  public void scheduleFixedDelayTask() throws InterruptedException {
    for (NameEntry nameEntry : nameConfig.getWatchlist()) {
      NameAuctionResult nameAuctionResult =
          aeternityService.mdw.blockingGetNameAuction(nameEntry.getName());
      if (nameAuctionResult.getRootErrorMessage() == null) {
        log.info("{}: found active auction", nameEntry.getName());
        if (aeternityServiceConfiguration
            .getKeyPair()
            .getAddress()
            .equals(nameAuctionResult.getCurrentBid().getClaimer())) {
          log.info("{}: we are currently the highest bidder", nameEntry.getName());
        } else {
          log.info("{}: we have been outbid and need to perform a new claim", nameEntry.getName());
          log.info(
              "{}: current highest bid: {}",
              nameEntry.getName(),
              nameAuctionResult.getCurrentBid().getNameFee());
          if (nameEntry
                  .getMaxBid()
                  .compareTo(AENS.getNextNameFee(nameAuctionResult.getCurrentBid().getNameFee()))
              == -1) {
            log.info("{}: maxBid value exceeded. skipping the claim", nameEntry.getName());
          } else {
            performClaim(nameEntry.getName(), nameAuctionResult.getCurrentBid().getNameFee());
          }
        }
      } else {
        NameEntryResult nameEntryResult =
            aeternityService.names.blockingGetNameId(nameEntry.getName());
        if (nameEntryResult.getRootErrorMessage() != null) {
          log.info("{}: name was never claimed before", nameEntry.getName());
          initialActions(nameEntry.getName());
        } else {
          log.info("{}: name already claimed: {}", nameEntry.getName(), nameEntryResult);
          /** only perform update if we are the owner and if the name should be updated */
          if (nameEntryResult
                  .getOwner()
                  .equals(aeternityServiceConfiguration.getKeyPair().getAddress())
              && nameEntry.isUpdate()) {
            performUpdate(nameEntryResult.getId(), nameConfig.getPointers());
            nameEntryResult = aeternityService.names.blockingGetNameId(nameEntry.getName());
            log.info("{}: updated: {}", nameEntry.getName(), nameEntryResult);
          } else {
            log.info("{}: skip update", nameEntry.getName());
          }
        }
      }
    }
    log.info("account: {}", aeternityService.accounts.blockingGetAccount());
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
            .accountId(aeternityServiceConfiguration.getKeyPair().getAddress())
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
   * @param pointers the pointer map
   * @throws InterruptedException
   */
  private void performUpdate(final String nameId, final Map<String, String> pointers)
      throws InterruptedException {
    NameUpdateTransactionModel nameUpdateTransactionModel =
        NameUpdateTransactionModel.builder()
            .accountId(aeternityServiceConfiguration.getKeyPair().getAddress())
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
            .accountId(aeternityServiceConfiguration.getKeyPair().getAddress())
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
            .accountId(aeternityServiceConfiguration.getKeyPair().getAddress())
            .nonce(getNextNonce())
            .ttl(BigInteger.ZERO)
            .build();
    log.info("NameClaimTx-model: {}", nameClaimTransactionModel);
    postTransactionResult =
        aeternityService.transactions.blockingPostTransaction(nameClaimTransactionModel);
    log.info("NameClaimTx-hash: {}", postTransactionResult.getTxHash());
    log.info("successfully claimed: {}", name);
  }

  private BigInteger getNextNonce() {
    AccountResult accountResult = aeternityService.accounts.blockingGetAccount();
    if (accountResult.getRootErrorMessage() != null) {
      return BigInteger.ONE;
    }
    return accountResult.getNonce().add(BigInteger.ONE);
  }
}
