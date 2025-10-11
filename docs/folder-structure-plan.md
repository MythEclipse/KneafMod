# Rencana Struktur Folder dan Aturan Pengembangan KneafCore

## Struktur Folder Saat Ini

```
src/main/java/com/kneaf/core/
├── config/                    # Konfigurasi sistem
│   ├── core/                 # Core configuration utilities
│   ├── performance/          # Performance configurations
│   ├── chunkstorage/         # Chunk storage configs
│   └── ...
├── data/                     # Data models dan entities
├── exceptions/               # Exception hierarchy
├── performance/              # Performance monitoring & optimization
├── unifiedbridge/            # Native bridge implementations
└── utils/                    # Utility classes
```

## Struktur Folder yang Dianjurkan

### Struktur Utama (Flattened Hierarchy)

```
src/main/java/com/kneaf/core/
├── core/                     # Core system components
│   ├── KneafCore.java       # Main mod class
│   ├── SystemManager.java   # System facade
│   ├── ModInitializer.java  # Initialization logic
│   ├── EventHandler.java    # Event handling
│   └── LifecycleManager.java # Lifecycle management
├── config/                   # Unified configuration system
│   ├── ConfigurationManager.java
│   ├── ConfigurationUtils.java
│   ├── PerformanceConfig.java    # Single consolidated config
│   ├── ChunkStorageConfig.java
│   ├── SwapConfig.java
│   └── ResourceConfig.java
├── data/                     # Data models (unchanged)
├── exceptions/               # Exception hierarchy (simplified)
│   ├── BaseKneafException.java   # Base class with common builders
│   ├── core/                 # Core exceptions
│   ├── processing/           # Processing exceptions
│   └── storage/              # Storage exceptions
├── performance/              # Performance system
│   ├── PerformanceManager.java
│   ├── monitoring/           # Monitoring components
│   ├── core/                 # Core performance utilities
│   └── unified/              # Unified performance API
├── bridge/                   # Native bridge system (renamed)
│   ├── BridgeResultFactory.java  # NEW: Factory for results
│   ├── UnifiedBridge.java
│   ├── SynchronousBridge.java
│   ├── AsynchronousBridge.java
│   └── ZeroCopyBridge.java
├── chunkstorage/             # Chunk storage system
├── protocol/                 # Protocol handling
├── registry/                 # Registry management
└── utils/                    # Utilities (consolidated)
```

### Struktur Test

```
src/test/java/com/kneaf/core/
├── core/                     # Core component tests
├── config/                   # Configuration tests
├── performance/              # Performance tests
├── bridge/                   # Bridge tests
└── integration/              # Integration tests
```

### Struktur Resources

```
src/main/resources/
├── config/                   # Default configuration files
│   ├── kneaf-core.properties
│   ├── kneaf-performance.properties
│   └── kneaf-performance-ultra.properties
├── assets/kneafcore/         # Mod assets
└── META-INF/                 # Mod metadata
```

## Aturan Pengembangan

### 1. Package Organization Rules

#### Package Naming Conventions

- **core**: Komponen sistem utama
- **config**: Semua konfigurasi terkait
- **data**: Model data dan entities
- **exceptions**: Exception hierarchy
- **performance**: Performance monitoring dan optimization
- **bridge**: Native bridge implementations
- **utils**: Utility classes (maksimal 3 levels depth)

#### Package Depth Limits

- **Maximum depth**: 3 levels (e.g., `performance/monitoring/core`)
- **Exception**: Test packages boleh lebih dalam untuk mirroring main code

### 2. Class Organization Rules

#### Class Naming

- **Manager**: Untuk facade classes (`SystemManager`, `ConfigurationManager`)
- **Handler**: Untuk event/component handlers (`EventHandler`, `NetworkHandler`)
- **Factory**: Untuk factory classes (`BridgeResultFactory`)
- **Utils**: Untuk utility classes (`ConfigurationUtils`)
- **Config**: Untuk configuration classes (`PerformanceConfig`)

#### Class Responsibilities

- **Single Responsibility**: Satu class = satu tanggung jawab
- **Immutable Configs**: Configuration classes harus immutable dengan Builder
- **Factory Pattern**: Untuk object creation yang kompleks
- **Utility Classes**: Static methods only, private constructor

### 3. Code Quality Standards

#### DRY Compliance

- **No code duplication**: Gunakan utility methods atau inheritance
- **Common patterns**: Extract ke base classes atau utilities
- **Configuration parsing**: Centralized di `ConfigurationUtils`

#### SOLID Principles

- **Single Responsibility**: Maksimal 1 reason to change per class
- **Open/Closed**: Extensible via interfaces dan builders
- **Liskov Substitution**: Consistent interface implementations
- **Interface Segregation**: Small, focused interfaces
- **Dependency Inversion**: Depend on abstractions, not concretions

#### Naming Conventions

- **Methods**: camelCase, descriptive names
- **Fields**: camelCase, consistent across similar classes
- **Constants**: UPPER_SNAKE_CASE
- **Packages**: lowercase, no underscores

### 4. Configuration Management

#### Configuration Files

- **Location**: `src/main/resources/config/`
- **Naming**: `kneaf-{component}.properties`
- **Loading**: Via `ConfigurationManager` only

#### Configuration Classes

- **Immutability**: All config classes immutable
- **Builder Pattern**: Required for construction
- **Validation**: Runtime validation in constructors
- **Single Source**: No duplicate config classes

### 5. Exception Handling

#### Exception Hierarchy

```
BaseKneafException
├── KneafCoreException
├── NativeLibraryException
├── DatabaseException
├── AsyncProcessingException
└── OptimizedProcessingException
```

#### Exception Rules

- **Builder Pattern**: Consistent `withContext()` dan `withSuggestion()`
- **Base Class**: Common functionality di `BaseKneafException`
- **Context**: Always include context information
- **Logging**: Centralized logging di exception handlers

### 6. Bridge Pattern Implementation

#### BridgeResult Creation

- **Factory Pattern**: `BridgeResultFactory.createSuccess()`
- **No Direct Construction**: Never `new BridgeResult.Builder()`
- **Consistent Error Handling**: Standardized error result creation

#### Bridge Implementation

- **Interface Segregation**: Separate interfaces untuk sync/async
- **Error Handling**: Consistent error propagation
- **Resource Management**: Proper cleanup di lifecycle methods

### 7. Testing Standards

#### Test Organization

- **Mirror main code**: Test packages match main packages
- **Naming**: `{ClassName}Test.java`
- **Coverage**: Minimum 80% line coverage
- **Types**: Unit, Integration, Performance tests

#### Test Categories

- **Unit Tests**: Individual class testing
- **Integration Tests**: Component interaction testing
- **Performance Tests**: Benchmark dan stress testing
- **Regression Tests**: Bug fix validation

### 8. Documentation Requirements

#### Code Documentation

- **Class Javadoc**: Required untuk public classes
- **Method Javadoc**: Required untuk public methods
- **Field Documentation**: Complex fields perlu dokumentasi
- **Architecture Docs**: Update saat ada perubahan struktur

#### README Files

- **Package README**: Di setiap major package
- **Architecture README**: Overall system documentation
- **API Documentation**: Public API documentation

### 9. Build dan Deployment

#### Gradle Configuration

- **Dependencies**: Centralized version management
- **Tasks**: Standardized build tasks
- **Testing**: Automated test execution
- **Artifacts**: Consistent artifact naming

#### Release Process

- **Versioning**: Semantic versioning (MAJOR.MINOR.PATCH)
- **Changelog**: Required untuk setiap release
- **Migration Guide**: Untuk breaking changes
- **Rollback Plan**: Defined rollback procedures

### 10. Code Review Checklist

#### Pre-commit Checks

- [ ] Code compiles without warnings
- [ ] Unit tests pass
- [ ] Code coverage maintained
- [ ] No duplicate code (SonarQube check)
- [ ] Naming conventions followed

#### Review Criteria

- [ ] SOLID principles followed
- [ ] DRY principle maintained
- [ ] Documentation updated
- [ ] Tests added/modified
- [ ] Performance impact assessed

## Migrasi Plan

### Phase 1: Foundation (Week 1-2)

1. Create new package structure
2. Move core classes to new locations
3. Update import statements
4. Create BridgeResultFactory
5. Implement BaseKneafException

### Phase 2: Consolidation (Week 3-4)

1. Merge duplicate PerformanceConfig classes
2. Migrate to ConfigurationUtils
3. Update all bridge implementations
4. Standardize exception builders

### Phase 3: Cleanup (Week 5-6)

1. Remove old duplicate files
2. Update all references
3. Comprehensive testing
4. Documentation updates

### Phase 4: Optimization (Week 7-8)

1. Performance testing
2. Code quality improvements
3. Final documentation
4. Release preparation

## Monitoring dan Maintenance

### Metrics to Track

- **Code Duplication**: Target <5%
- **Cyclomatic Complexity**: Average <10
- **Test Coverage**: Target >85%
- **Build Time**: Monitor for regressions
- **Package Depth**: Maintain <4 levels

### Regular Reviews

- **Monthly**: Architecture review
- **Weekly**: Code quality metrics
- **Daily**: Build status monitoring
- **Per Release**: Dependency updates

## Kesimpulan

Rencana struktur folder ini bertujuan untuk:

1. Mengurangi duplikasi kode
2. Meningkatkan maintainability
3. Menstandardisasi development practices
4. Memperbaiki separation of concerns
5. Memfasilitasi future extensibility

Implementasi bertahap memastikan minimal disruption sambil memberikan continuous improvement.
