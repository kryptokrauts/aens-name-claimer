package com.kryptokrauts.config;

import com.kryptokrauts.aeternity.sdk.service.unit.UnitConversionService;
import com.kryptokrauts.aeternity.sdk.service.unit.impl.DefaultUnitConversionServiceImpl;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "names")
public class NameConfig {

  private static UnitConversionService unitConversionService =
      new DefaultUnitConversionServiceImpl();

  private Map<String, String> pointers = new HashMap<>();
  private List<NameEntry> watchlist = new ArrayList<>();

  @Data
  @ToString
  public static class NameEntry {
    private String name;
    private BigInteger maxBid = unitConversionService.toSmallestUnit("25");
    private boolean update = false;
  }
}
