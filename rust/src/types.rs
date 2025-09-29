use glam::Vec3A;
use serde::{Serialize, Deserialize, Serializer, Deserializer};
use serde::ser::SerializeStruct;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Aabb {
    pub min: Vec3A,
    pub max: Vec3A,
}

impl Aabb {
    pub fn new(min_x: f32, min_y: f32, min_z: f32, max_x: f32, max_y: f32, max_z: f32) -> Self {
        Self {
            min: Vec3A::new(min_x, min_y, min_z),
            max: Vec3A::new(max_x, max_y, max_z),
        }
    }

    pub fn contains_point(&self, point: &Vec3A) -> bool {
        point.x >= self.min.x && point.x <= self.max.x &&
        point.y >= self.min.y && point.y <= self.max.y &&
        point.z >= self.min.z && point.z <= self.max.z
    }

    pub fn intersects(&self, other: &Aabb) -> bool {
        self.min.x <= other.max.x && self.max.x >= other.min.x &&
        self.min.y <= other.max.y && self.max.y >= other.min.y &&
        self.min.z <= other.max.z && self.max.z >= other.min.z
    }
}

// Provide Serialize/Deserialize implementations that convert Vec3A to/from [f32;3]
impl Serialize for Aabb {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where S: Serializer {
        let min = [self.min.x, self.min.y, self.min.z];
        let max = [self.max.x, self.max.y, self.max.z];
        let mut state = serializer.serialize_struct("Aabb", 2)?;
        state.serialize_field("min", &min)?;
        state.serialize_field("max", &max)?;
        state.end()
    }
}

impl<'de> Deserialize<'de> for Aabb {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where D: Deserializer<'de> {
        #[derive(Deserialize)]
        struct RawAabb { min: [f32;3], max: [f32;3] }

        let raw = RawAabb::deserialize(deserializer)?;
        Ok(Aabb { min: Vec3A::new(raw.min[0], raw.min[1], raw.min[2]), max: Vec3A::new(raw.max[0], raw.max[1], raw.max[2]) })
    }
}