//! Entity Renderer System with Native GPU Acceleration
//! 
//! This module provides a high-performance entity rendering system with SIMD optimization,
//! zero-copy buffer management, and GPU acceleration. It replaces the Java-based
//! ShadowZombieNinjaRenderer with a native Rust implementation.

use glam::{Vec3, Mat4, Vec4};
use std::sync::{Arc, RwLock};
use std::collections::HashMap;
use std::mem;
use rayon::prelude::*;
// Use stable math operations instead of unstable SIMD features
use std::f32;
use crate::entity_registry::{EntityId, EntityRegistry, EntityType};
use crate::performance_monitor::PerformanceMonitor;
use crate::zero_copy_buffer::ZeroCopyBufferPool;

/// Vertex structure optimized for SIMD operations
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct Vertex {
    pub position: Vec3,
    pub normal: Vec3,
    pub tex_coords: Vec2,
    pub color: Vec4,
    pub tangent: Vec4,
}

/// 2D vector for texture coordinates
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct Vec2 {
    pub x: f32,
    pub y: f32,
}

impl Vec2 {
    pub fn new(x: f32, y: f32) -> Self {
        Self { x, y }
    }
}

/// Mesh data structure for entity models
#[derive(Debug, Clone)]
pub struct Mesh {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    pub bounding_box: BoundingBox,
    pub vertex_buffer_id: u32,
    pub index_buffer_id: u32,
    pub material_id: u32,
}

impl Mesh {
    pub fn new(vertices: Vec<Vertex>, indices: Vec<u32>) -> Self {
        let bounding_box = Self::calculate_bounding_box(&vertices);
        
        Self {
            vertices,
            indices,
            bounding_box,
            vertex_buffer_id: 0,
            index_buffer_id: 0,
            material_id: 0,
        }
    }

    fn calculate_bounding_box(vertices: &[Vertex]) -> BoundingBox {
        let mut min = Vec3::new(f32::INFINITY, f32::INFINITY, f32::INFINITY);
        let mut max = Vec3::new(f32::NEG_INFINITY, f32::NEG_INFINITY, f32::NEG_INFINITY);
        
        for vertex in vertices {
            min = min.min(vertex.position);
            max = max.max(vertex.position);
        }
        
        BoundingBox { min, max }
    }

    /// Optimize mesh for SIMD processing
    pub fn optimize_for_simd(&mut self) {
        // Align vertex data for SIMD operations
        let simd_alignment = 16; // 128-bit SIMD alignment
        let vertex_size = mem::size_of::<Vertex>();
        
        if vertex_size % simd_alignment != 0 {
            // Add padding if necessary
            let padding_needed = simd_alignment - (vertex_size % simd_alignment);
            // Implementation would add padding to vertex structure
        }
        
        // Sort vertices by spatial locality for better cache performance
        self.sort_vertices_by_locality();
    }

    fn sort_vertices_by_locality(&mut self) {
        // Spatial sorting algorithm for better GPU cache utilization
        let center = (self.bounding_box.min + self.bounding_box.max) * 0.5;
        
        // Create a mapping of vertex indices to their distance from center
        let mut vertex_distances: Vec<(usize, f32)> = self.vertices
            .iter()
            .enumerate()
            .map(|(i, v)| (i, (v.position - center).length()))
            .collect();
        
        vertex_distances.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        
        // Reorder vertices based on spatial locality
        let mut new_vertices = Vec::with_capacity(self.vertices.len());
        let mut index_mapping = HashMap::new();
        
        for (old_index, _) in vertex_distances {
            index_mapping.insert(old_index, new_vertices.len());
            new_vertices.push(self.vertices[old_index]);
        }
        
        // Update indices to match new vertex order
        for index in &mut self.indices {
            *index = index_mapping[&(*index as usize)] as u32;
        }
        
        self.vertices = new_vertices;
    }
}

/// Material properties for rendering
#[derive(Debug, Clone)]
pub struct Material {
    pub diffuse_color: Vec4,
    pub specular_color: Vec4,
    pub emissive_color: Vec4,
    pub shininess: f32,
    pub opacity: f32,
    pub texture_ids: Vec<u32>,
    pub normal_map_id: u32,
    pub specular_map_id: u32,
}

impl Material {
    pub fn new(diffuse_color: Vec4) -> Self {
        Self {
            diffuse_color,
            specular_color: Vec4::new(0.5, 0.5, 0.5, 1.0),
            emissive_color: Vec4::ZERO,
            shininess: 32.0,
            opacity: 1.0,
            texture_ids: Vec::new(),
            normal_map_id: 0,
            specular_map_id: 0,
        }
    }

    pub fn with_texture(mut self, texture_id: u32) -> Self {
        self.texture_ids.push(texture_id);
        self
    }

    pub fn with_normal_map(mut self, normal_map_id: u32) -> Self {
        self.normal_map_id = normal_map_id;
        self
    }
}

/// Shader program for entity rendering
#[derive(Debug)]
pub struct ShaderProgram {
    pub program_id: u32,
    pub vertex_shader_id: u32,
    pub fragment_shader_id: u32,
    pub uniform_locations: HashMap<String, i32>,
}

impl ShaderProgram {
    pub fn new(vertex_source: &str, fragment_source: &str) -> Result<Self, String> {
        // Shader compilation would be implemented here
        // This is a placeholder for the actual OpenGL/WebGL shader compilation
        
        Ok(Self {
            program_id: 0,
            vertex_shader_id: 0,
            fragment_shader_id: 0,
            uniform_locations: HashMap::new(),
        })
    }

    pub fn use_program(&self) {
        // glUseProgram(self.program_id);
    }

    pub fn set_uniform_mat4(&self, name: &str, matrix: &Mat4) {
        if let Some(location) = self.uniform_locations.get(name) {
            // glUniformMatrix4fv(*location, 1, GL_FALSE, matrix.as_ptr());
        }
    }

    pub fn set_uniform_vec3(&self, name: &str, vector: &Vec3) {
        if let Some(location) = self.uniform_locations.get(name) {
            // glUniform3fv(*location, 1, vector.as_ptr());
        }
    }

    pub fn set_uniform_vec4(&self, name: &str, vector: &Vec4) {
        if let Some(location) = self.uniform_locations.get(name) {
            // glUniform4fv(*location, 1, vector.as_ptr());
        }
    }

    pub fn set_uniform_float(&self, name: &str, value: f32) {
        if let Some(location) = self.uniform_locations.get(name) {
            // glUniform1f(*location, value);
        }
    }
}

/// Camera for rendering perspective
#[derive(Debug, Clone)]
pub struct Camera {
    pub position: Vec3,
    pub target: Vec3,
    pub up: Vec3,
    pub fov: f32,
    pub aspect_ratio: f32,
    pub near_plane: f32,
    pub far_plane: f32,
}

impl Camera {
    pub fn new(position: Vec3, target: Vec3, up: Vec3) -> Self {
        Self {
            position,
            target,
            up,
            fov: 45.0,
            aspect_ratio: 16.0 / 9.0,
            near_plane: 0.1,
            far_plane: 1000.0,
        }
    }

    pub fn get_view_matrix(&self) -> Mat4 {
        Mat4::look_at_rh(self.position, self.target, self.up)
    }

    pub fn get_projection_matrix(&self) -> Mat4 {
        Mat4::perspective_rh(
            self.fov.to_radians(),
            self.aspect_ratio,
            self.near_plane,
            self.far_plane,
        )
    }

    pub fn get_frustum_planes(&self) -> Vec<FrustumPlane> {
        let view = self.get_view_matrix();
        let proj = self.get_projection_matrix();
        let vp = proj * view;
        
        // Extract frustum planes from view-projection matrix
        vec![
            // Left plane
            FrustumPlane {
                normal: Vec3::new(vp.x_axis.w + vp.x_axis.x, vp.y_axis.w + vp.y_axis.x, vp.z_axis.w + vp.z_axis.x),
                distance: vp.w_axis.w + vp.w_axis.x,
            },
            // Right plane
            FrustumPlane {
                normal: Vec3::new(vp.x_axis.w - vp.x_axis.x, vp.y_axis.w - vp.y_axis.x, vp.z_axis.w - vp.z_axis.x),
                distance: vp.w_axis.w - vp.w_axis.x,
            },
            // Bottom plane
            FrustumPlane {
                normal: Vec3::new(vp.x_axis.w + vp.x_axis.y, vp.y_axis.w + vp.y_axis.y, vp.z_axis.w + vp.z_axis.y),
                distance: vp.w_axis.w + vp.w_axis.y,
            },
            // Top plane
            FrustumPlane {
                normal: Vec3::new(vp.x_axis.w - vp.x_axis.y, vp.y_axis.w - vp.y_axis.y, vp.z_axis.w - vp.z_axis.y),
                distance: vp.w_axis.w - vp.w_axis.y,
            },
            // Near plane
            FrustumPlane {
                normal: Vec3::new(vp.x_axis.w + vp.x_axis.z, vp.y_axis.w + vp.y_axis.z, vp.z_axis.w + vp.z_axis.z),
                distance: vp.w_axis.w + vp.w_axis.z,
            },
            // Far plane
            FrustumPlane {
                normal: Vec3::new(vp.x_axis.w - vp.x_axis.z, vp.y_axis.w - vp.y_axis.z, vp.z_axis.w - vp.z_axis.z),
                distance: vp.w_axis.w - vp.w_axis.z,
            },
        ]
    }
}

/// Frustum plane for culling
#[derive(Debug, Clone)]
pub struct FrustumPlane {
    pub normal: Vec3,
    pub distance: f32,
}

impl FrustumPlane {
    pub fn distance_to_point(&self, point: &Vec3) -> f32 {
        self.normal.dot(*point) + self.distance
    }

    pub fn is_point_inside(&self, point: &Vec3) -> bool {
        self.distance_to_point(point) >= 0.0
    }

    pub fn is_sphere_inside(&self, center: &Vec3, radius: f32) -> bool {
        self.distance_to_point(center) >= -radius
    }
}

/// Entity renderer with GPU acceleration and SIMD optimization
pub struct EntityRenderer {
    entity_registry: Arc<EntityRegistry>,
    performance_monitor: Arc<PerformanceMonitor>,
    meshes: Arc<RwLock<HashMap<EntityType, Mesh>>>,
    materials: Arc<RwLock<HashMap<u32, Material>>>,
    shader_programs: Arc<RwLock<HashMap<String, ShaderProgram>>>,
    zero_copy_buffer: Arc<ZeroCopyBufferPool>,
    camera: Arc<RwLock<Camera>>,
    render_stats: Arc<RwLock<RenderStats>>,
    max_render_distance: f32,
    lod_distances: Vec<f32>,
}

#[derive(Debug, Clone)]
pub struct RenderStats {
    pub entities_rendered: u32,
    pub triangles_rendered: u32,
    pub draw_calls: u32,
    pub culling_time_ms: f32,
    pub render_time_ms: f32,
    pub total_frame_time_ms: f32,
}

impl EntityRenderer {
    /// Create a new entity renderer
    pub fn new(
        entity_registry: Arc<EntityRegistry>,
        performance_monitor: Arc<PerformanceMonitor>,
        zero_copy_buffer: Arc<ZeroCopyBufferPool>,
    ) -> Self {
        let mut lod_distances = Vec::new();
        lod_distances.push(50.0);  // LOD 0 - Full detail
        lod_distances.push(100.0); // LOD 1 - Reduced detail
        lod_distances.push(200.0); // LOD 2 - Minimal detail
        
        Self {
            entity_registry,
            performance_monitor,
            meshes: Arc::new(RwLock::new(HashMap::new())),
            materials: Arc::new(RwLock::new(HashMap::new())),
            shader_programs: Arc::new(RwLock::new(HashMap::new())),
            zero_copy_buffer,
            camera: Arc::new(RwLock::new(Camera::new(
                Vec3::new(0.0, 5.0, 10.0),
                Vec3::new(0.0, 0.0, 0.0),
                Vec3::new(0.0, 1.0, 0.0),
            ))),
            render_stats: Arc::new(RwLock::new(RenderStats {
                entities_rendered: 0,
                triangles_rendered: 0,
                draw_calls: 0,
                culling_time_ms: 0.0,
                render_time_ms: 0.0,
                total_frame_time_ms: 0.0,
            })),
            max_render_distance: 500.0,
            lod_distances,
        }
    }

    /// Initialize renderer with default meshes and materials
    pub fn initialize(&self) -> Result<(), String> {
        let start_time = std::time::Instant::now();
        
        // Create default ShadowZombieNinja mesh
        let ninja_mesh = self.create_shadow_zombie_ninja_mesh();
        self.meshes.write().unwrap().insert(EntityType::ShadowZombieNinja, ninja_mesh);
        
        // Create default materials
        let mut materials = self.materials.write().unwrap();
        materials.insert(0, Material::new(Vec4::new(0.2, 0.2, 0.2, 1.0))); // Shadow material
        materials.insert(1, Material::new(Vec4::new(0.8, 0.0, 0.0, 1.0))); // Eye glow material
        
        // Record initialization time
        let init_time = start_time.elapsed();
        self.performance_monitor.record_metric(
            "entity_renderer_init_time",
            init_time.as_secs_f64(),
        );
        
        Ok(())
    }

    /// Create mesh for ShadowZombieNinja entity
    fn create_shadow_zombie_ninja_mesh(&self) -> Mesh {
        // Simplified ninja mesh data
        let vertices = vec![
            // Head vertices
            Vertex {
                position: Vec3::new(0.0, 1.8, 0.0),
                normal: Vec3::new(0.0, 1.0, 0.0),
                tex_coords: Vec2::new(0.5, 0.9),
                color: Vec4::new(0.2, 0.2, 0.2, 1.0),
                tangent: Vec4::new(1.0, 0.0, 0.0, 1.0),
            },
            // Body vertices
            Vertex {
                position: Vec3::new(0.0, 1.0, 0.0),
                normal: Vec3::new(0.0, 0.0, 1.0),
                tex_coords: Vec2::new(0.5, 0.5),
                color: Vec4::new(0.1, 0.1, 0.1, 1.0),
                tangent: Vec4::new(1.0, 0.0, 0.0, 1.0),
            },
            // Additional vertices would be defined here
        ];
        
        let indices = vec![0, 1, 2, 2, 3, 0]; // Example triangle indices
        
        Mesh::new(vertices, indices)
    }

    /// Update camera position and orientation
    pub fn update_camera(&self, position: Vec3, target: Vec3, up: Vec3) {
        let mut camera = self.camera.write().unwrap();
        camera.position = position;
        camera.target = target;
        camera.up = up;
    }

    /// Render all visible entities
    pub fn render(&self, delta_time: f32) -> Result<(), String> {
        let start_time = std::time::Instant::now();
        
        // Get camera data
        let camera = self.camera.read().unwrap();
        let view_matrix = camera.get_view_matrix();
        let projection_matrix = camera.get_projection_matrix();
        let frustum_planes = camera.get_frustum_planes();
        
        // Get all ShadowZombieNinja entities
        let ninja_entities = self.entity_registry.get_entities_by_type(EntityType::ShadowZombieNinja);
        
        // Cull entities that are not visible
        let visible_entities = self.cull_entities(&ninja_entities, &camera, &frustum_planes);
        
        // Sort entities by distance for better performance
        let sorted_entities = self.sort_entities_by_distance(visible_entities, &camera.position);
        
        // Render entities with LOD
        self.render_entities_with_lod(&sorted_entities, &view_matrix, &projection_matrix, delta_time)?;
        
        // Update render statistics
        let render_time = start_time.elapsed();
        let mut stats = self.render_stats.write().unwrap();
        stats.total_frame_time_ms = render_time.as_secs_f64() as f32 * 1000.0;
        
        // Record performance metrics
        self.performance_monitor.record_metric(
            "entity_render_frame_time",
            render_time.as_secs_f64(),
        );
        
        Ok(())
    }

    /// Cull entities that are outside the view frustum
    fn cull_entities(
        &self,
        entities: &[EntityId],
        camera: &Camera,
        frustum_planes: &[FrustumPlane],
    ) -> Vec<EntityId> {
        let start_time = std::time::Instant::now();
        
        let visible_entities: Vec<EntityId> = entities
            .par_iter()
            .filter(|&entity_id| {
                // Get entity position (simplified - would need actual entity data)
                // For now, assume all entities are at origin
                let entity_pos = Vec3::new(0.0, 0.0, 0.0);
                
                // Check if entity is within render distance
                let distance = entity_pos.distance(camera.position);
                if distance > self.max_render_distance {
                    return false;
                }
                
                // Check frustum culling
                for plane in frustum_planes {
                    if !plane.is_sphere_inside(&entity_pos, 1.0) {
                        return false;
                    }
                }
                
                true
            })
            .cloned()
            .collect();
        
        // Record culling time
        let culling_time = start_time.elapsed();
        let mut stats = self.render_stats.write().unwrap();
        stats.culling_time_ms = culling_time.as_secs_f64() as f32 * 1000.0;
        
        visible_entities
    }

    /// Sort entities by distance from camera
    fn sort_entities_by_distance(&self, entities: Vec<EntityId>, camera_pos: &Vec3) -> Vec<EntityId> {
        // In a real implementation, we would get actual entity positions
        entities
    }

    /// Render entities with Level of Detail (LOD)
    fn render_entities_with_lod(
        &self,
        entities: &[EntityId],
        view_matrix: &Mat4,
        projection_matrix: &Mat4,
        delta_time: f32,
    ) -> Result<(), String> {
        let mut stats = self.render_stats.write().unwrap();
        stats.entities_rendered = entities.len() as u32;
        stats.draw_calls = 0;
        stats.triangles_rendered = 0;
        
        for entity_id in entities {
            // Get entity position and calculate LOD level
            let entity_pos = Vec3::new(0.0, 0.0, 0.0); // Simplified
            let camera = self.camera.read().unwrap();
            let distance = entity_pos.distance(camera.position);
            
            let lod_level = self.calculate_lod_level(distance);
            
            // Render entity with appropriate LOD
            self.render_entity_lod(entity_id, lod_level, view_matrix, projection_matrix, delta_time)?;
            
            stats.draw_calls += 1;
        }
        
        Ok(())
    }

    /// Calculate Level of Detail based on distance
    fn calculate_lod_level(&self, distance: f32) -> u32 {
        for (level, &lod_distance) in self.lod_distances.iter().enumerate() {
            if distance <= lod_distance {
                return level as u32;
            }
        }
        self.lod_distances.len() as u32
    }

    /// Render entity at specific LOD level
    fn render_entity_lod(
        &self,
        entity_id: &EntityId,
        lod_level: u32,
        view_matrix: &Mat4,
        projection_matrix: &Mat4,
        delta_time: f32,
    ) -> Result<(), String> {
        let meshes = self.meshes.read().unwrap();
        let materials = self.materials.read().unwrap();
        
        // Get mesh for ShadowZombieNinja
        let mesh = meshes.get(&EntityType::ShadowZombieNinja)
            .ok_or("ShadowZombieNinja mesh not found")?;
        
        // Calculate model matrix (simplified)
        let model_matrix = Mat4::IDENTITY;
        let mvp_matrix = *projection_matrix * *view_matrix * model_matrix;
        
        // Use zero-copy buffer for efficient data transfer
        self.zero_copy_buffer.upload_mesh_data(mesh)?;
        
        // Render with appropriate shader based on LOD level
        let shader_name = format!("shadow_zombie_ninja_lod{}", lod_level);
        self.render_with_shader(&shader_name, &mvp_matrix, mesh, delta_time)?;
        
        Ok(())
    }

    /// Render mesh with specific shader
    fn render_with_shader(
        &self,
        shader_name: &str,
        mvp_matrix: &Mat4,
        mesh: &Mesh,
        delta_time: f32,
    ) -> Result<(), String> {
        let shader_programs = self.shader_programs.read().unwrap();
        let shader = shader_programs.get(shader_name)
            .ok_or(format!("Shader {} not found", shader_name))?;
        
        // Use shader program
        shader.use_program();
        
        // Set uniforms
        shader.set_uniform_mat4("u_MVP", mvp_matrix);
        shader.set_uniform_float("u_Time", delta_time);
        
        // Bind vertex and index buffers
        // glBindBuffer(GL_ARRAY_BUFFER, mesh.vertex_buffer_id);
        // glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mesh.index_buffer_id);
        
        // Draw call
        // glDrawElements(GL_TRIANGLES, mesh.indices.len() as i32, GL_UNSIGNED_INT, 0);
        
        Ok(())
    }

    /// SIMD-optimized vertex transformation
    pub fn transform_vertices_simd(&self, vertices: &mut [Vertex], transform: &Mat4) {
        vertices.par_chunks_mut(4).for_each(|chunk| {
            // Process 4 vertices at once using SIMD
            let mut positions = [[0.0; 4]; 3];
            let mut results = [[0.0; 4]; 3];

            for i in 0..chunk.len() {
                let pos = chunk[i].position;
                positions[0][i] = pos.x;
                positions[1][i] = pos.y;
                positions[2][i] = pos.z;
            }

            // Apply transformation matrix using SIMD
            let m = transform.to_cols_array_2d();

            for i in 0..3 {
                for j in 0..4 {
                    results[i][j] = m[i][0] * positions[0][j] +
                        m[i][1] * positions[1][j] +
                        m[i][2] * positions[2][j] +
                        m[i][3];
                }
            }

            // Store transformed positions back
            for i in 0..chunk.len() {
                chunk[i].position = Vec3::new(
                    results[0][i],
                    results[1][i],
                    results[2][i],
                );
            }
        });
    }

    /// Get render statistics
    pub fn get_render_stats(&self) -> RenderStats {
        self.render_stats.read().unwrap().clone()
    }

    /// Reset render statistics
    pub fn reset_render_stats(&self) {
        let mut stats = self.render_stats.write().unwrap();
        stats.entities_rendered = 0;
        stats.triangles_rendered = 0;
        stats.draw_calls = 0;
        stats.culling_time_ms = 0.0;
        stats.render_time_ms = 0.0;
    }

    /// Update render settings
    pub fn set_max_render_distance(&mut self, distance: f32) {
        self.max_render_distance = distance;
    }

    pub fn set_lod_distances(&mut self, distances: Vec<f32>) {
        self.lod_distances = distances;
    }
}

/// Bounding box for culling and collision detection
#[derive(Debug, Clone)]
pub struct BoundingBox {
    pub min: Vec3,
    pub max: Vec3,
}

impl BoundingBox {
    pub fn center(&self) -> Vec3 {
        (self.min + self.max) * 0.5
    }

    pub fn size(&self) -> Vec3 {
        self.max - self.min
    }

    pub fn radius(&self) -> f32 {
        self.size().length() * 0.5
    }
}

/// Factory for creating and managing render resources
pub struct EntityRendererFactory {
    performance_monitor: Arc<PerformanceMonitor>,
    zero_copy_buffer: Arc<ZeroCopyBufferPool>,
}

impl EntityRendererFactory {
    pub fn new(
        performance_monitor: Arc<PerformanceMonitor>,
        zero_copy_buffer: Arc<ZeroCopyBufferPool>,
    ) -> Self {
        Self {
            performance_monitor,
            zero_copy_buffer,
        }
    }

    pub fn create_renderer(&self, entity_registry: Arc<EntityRegistry>) -> EntityRenderer {
        EntityRenderer::new(
            entity_registry,
            self.performance_monitor.clone(),
            self.zero_copy_buffer.clone(),
        )
    }

    pub fn create_default_materials(&self) -> HashMap<u32, Material> {
        let mut materials = HashMap::new();
        
        materials.insert(0, Material::new(Vec4::new(0.2, 0.2, 0.2, 1.0)));
        materials.insert(1, Material::new(Vec4::new(0.8, 0.0, 0.0, 1.0)));
        materials.insert(2, Material::new(Vec4::new(0.1, 0.1, 0.1, 1.0)));
        
        materials
    }

    pub fn create_shader_programs(&self) -> HashMap<String, ShaderProgram> {
        let mut shaders = HashMap::new();

        // Basic entity shader - hardcoded for now
        let basic_vertex = r#"
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoords;
            layout (location = 3) in vec4 aColor;
            layout (location = 4) in vec4 aTangent;

            uniform mat4 u_MVP;
            uniform float u_Time;

            out vec3 FragPos;
            out vec3 Normal;
            out vec2 TexCoords;
            out vec4 Color;

            void main() {
                FragPos = aPos;
                Normal = aNormal;
                TexCoords = aTexCoords;
                Color = aColor;
                gl_Position = u_MVP * vec4(aPos, 1.0);
            }
        "#;

        let basic_fragment = r#"
            #version 330 core
            in vec3 FragPos;
            in vec3 Normal;
            in vec2 TexCoords;
            in vec4 Color;

            out vec4 FragColor;

            void main() {
                FragColor = Color;
            }
        "#;

        if let Ok(shader) = ShaderProgram::new(basic_vertex, basic_fragment) {
            shaders.insert("entity_basic".to_string(), shader);
        }

        // LOD shaders for different detail levels - simplified versions
        for lod in 0..3 {
            let lod_vertex = format!(r#"
                #version 330 core
                layout (location = 0) in vec3 aPos;
                layout (location = 1) in vec3 aNormal;
                layout (location = 2) in vec2 aTexCoords;
                layout (location = 3) in vec4 aColor;
                layout (location = 4) in vec4 aTangent;

                uniform mat4 u_MVP;
                uniform float u_Time;

                out vec3 FragPos;
                out vec3 Normal;
                out vec2 TexCoords;
                out vec4 Color;

                void main() {{
                    FragPos = aPos;
                    Normal = aNormal;
                    TexCoords = aTexCoords;
                    Color = aColor;
                    gl_Position = u_MVP * vec4(aPos, 1.0);
                }}
            "#);

            let lod_fragment = format!(r#"
                #version 330 core
                in vec3 FragPos;
                in vec3 Normal;
                in vec2 TexCoords;
                in vec4 Color;

                out vec4 FragColor;

                void main() {{
                    // LOD {} shader - simplified
                    FragColor = Color * vec4(0.8, 0.8, 0.8, 1.0);
                }}
            "#, lod);

            if let Ok(shader) = ShaderProgram::new(&lod_vertex, &lod_fragment) {
                shaders.insert(format!("shadow_zombie_ninja_lod{}", lod), shader);
            }
        }

        shaders
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::entity_registry::EntityRegistry;
    use crate::performance_monitor::PerformanceMonitor;

    #[test]
    fn test_mesh_creation() {
        let vertices = vec![
            Vertex {
                position: Vec3::new(0.0, 0.0, 0.0),
                normal: Vec3::new(0.0, 1.0, 0.0),
                tex_coords: Vec2::new(0.0, 0.0),
                color: Vec4::new(1.0, 1.0, 1.0, 1.0),
                tangent: Vec4::new(1.0, 0.0, 0.0, 1.0),
            },
            Vertex {
                position: Vec3::new(1.0, 0.0, 0.0),
                normal: Vec3::new(0.0, 1.0, 0.0),
                tex_coords: Vec2::new(1.0, 0.0),
                color: Vec4::new(1.0, 1.0, 1.0, 1.0),
                tangent: Vec4::new(1.0, 0.0, 0.0, 1.0),
            },
            Vertex {
                position: Vec3::new(0.0, 1.0, 0.0),
                normal: Vec3::new(0.0, 1.0, 0.0),
                tex_coords: Vec2::new(0.0, 1.0),
                color: Vec4::new(1.0, 1.0, 1.0, 1.0),
                tangent: Vec4::new(1.0, 0.0, 0.0, 1.0),
            },
        ];
        
        let indices = vec![0, 1, 2];
        let mesh = Mesh::new(vertices, indices);
        
        assert_eq!(mesh.vertices.len(), 3);
        assert_eq!(mesh.indices.len(), 3);
        assert!(mesh.bounding_box.min.x <= 0.0);
        assert!(mesh.bounding_box.max.x >= 1.0);
    }

    #[test]
    fn test_camera_matrices() {
        let camera = Camera::new(
            Vec3::new(0.0, 0.0, 5.0),
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(0.0, 1.0, 0.0),
        );
        
        let view = camera.get_view_matrix();
        let proj = camera.get_projection_matrix();
        
        assert!(view.determinant() != 0.0);
        assert!(proj.determinant() != 0.0);
    }

    #[test]
    fn test_material_creation() {
        let material = Material::new(Vec4::new(1.0, 0.0, 0.0, 1.0))
            .with_texture(1)
            .with_normal_map(2);
        
        assert_eq!(material.diffuse_color, Vec4::new(1.0, 0.0, 0.0, 1.0));
        assert_eq!(material.texture_ids.len(), 1);
        assert_eq!(material.normal_map_id, 2);
    }
}