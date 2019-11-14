package com.kryptokrauts.config;

import com.kryptokrauts.aeternity.sdk.util.UnitConversionUtil;
import com.kryptokrauts.aeternity.sdk.util.UnitConversionUtil.Unit;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "names")
public class NameConfig {

  private List<NameEntry> watchlist = new ArrayList<>();

  @Data
  @ToString
  public static class NameEntry {
    private String name;
    private BigInteger maxBid = UnitConversionUtil.toAettos("25", Unit.AE).toBigInteger();
    private boolean update = false;
  }
}
