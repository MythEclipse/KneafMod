package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test stub for KneafCore to provide the same static field shape as the main
 * mod class. This prevents NoSuchFieldError when classes refer to
 * `KneafCore.LOGGER` during test initialization.
 */
public class KneafCore {
    public static final Logger LOGGER = LoggerFactory.getLogger("KneafCoreTestStub");
}
