package com.kneaf.core.performance.core;

import java.util.List;

/** Result of mob AI processing operation. */
public class MobProcessResult {
  private final List<Long> disableList;
  private final List<Long> simplifyList;

  public MobProcessResult(List<Long> disableList, List<Long> simplifyList) {
    this.disableList = disableList;
    this.simplifyList = simplifyList;
  }

  public List<Long> getDisableList() {
    return disableList;
  }

  public List<Long> getSimplifyList() {
    return simplifyList;
  }
}
