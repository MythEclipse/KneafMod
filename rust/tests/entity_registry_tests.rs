//! Tests for Entity Registry System

use rustperf::entity_registry::{Component, EntityRegistry, EntityType};
use rustperf::performance_monitor::PerformanceMonitor;
use std::sync::Arc;

#[derive(Debug, Clone)]
struct TestComponent {
    value: i32,
}

impl Component for TestComponent {}

#[test]
fn test_entity_creation() {
    let monitor = Arc::new(PerformanceMonitor::new());
    let registry = EntityRegistry::new(monitor);

    let entity_id = registry.create_entity(EntityType::ShadowZombieNinja);
    assert!(registry.entity_exists(entity_id));

    let entity = registry.get_entity(entity_id).unwrap();
    assert_eq!(entity.entity_type, EntityType::ShadowZombieNinja);
    assert!(entity.active);
}

#[test]
fn test_component_management() {
    let monitor = Arc::new(PerformanceMonitor::new());
    let registry = EntityRegistry::new(monitor);

    let entity_id = registry.create_entity(EntityType::ShadowZombieNinja);
    let component = TestComponent { value: 42 };

    assert!(registry.add_component(entity_id, component));

    let retrieved = registry.get_component::<TestComponent>(entity_id);
    assert!(retrieved.is_some());
    assert_eq!(retrieved.unwrap().value, 42);

    assert!(registry.remove_component::<TestComponent>(entity_id));
    assert!(registry.get_component::<TestComponent>(entity_id).is_none());
}

#[test]
fn test_entity_removal() {
    let monitor = Arc::new(PerformanceMonitor::new());
    let registry = EntityRegistry::new(monitor);

    let entity_id = registry.create_entity(EntityType::ShadowZombieNinja);
    assert!(registry.remove_entity(entity_id));
    assert!(!registry.entity_exists(entity_id));
}
