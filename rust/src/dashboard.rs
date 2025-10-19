//! Dashboard - Visualisasi metrik untuk performance monitoring
//! 
//! Modul ini menyediakan sistem visualisasi metrik dengan support untuk
//! real-time updates, multiple chart types, dan export capabilities.

use std::sync::Arc;
use std::sync::RwLock;
use dashmap::DashMap;
use chrono::{DateTime, Utc};
use serde::{Serialize, Deserialize};

/// Tipe chart yang didukung
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ChartType {
    Line,
    Bar,
    Area,
    Pie,
    Scatter,
    Histogram,
    Gauge,
}

/// Tipe data untuk visualisasi
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DashboardData {
    TimeSeries(Vec<(DateTime<Utc>, f64)>),
    Category(Vec<(String, f64)>),
    Scatter(Vec<(f64, f64)>),
    SingleValue(f64),
}

/// Konfigurasi untuk dashboard widget
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WidgetConfig {
    /// Nama widget
    pub name: String,
    /// Tipe chart
    pub chart_type: ChartType,
    /// Data yang ditampilkan
    pub data: DashboardData,
    /// Warna untuk visualisasi
    pub colors: Vec<String>,
    /// Ukuran widget (width, height)
    pub size: (u32, u32),
    /// Posisi widget (x, y)
    pub position: (u32, u32),
    /// Judul widget
    pub title: String,
    /// Deskripsi widget
    pub description: Option<String>,
    /// Update interval dalam detik
    pub update_interval: u64,
    /// Enable real-time updates
    pub real_time_enabled: bool,
}

/// Layout untuk dashboard
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DashboardLayout {
    /// Nama layout
    pub name: String,
    /// Grid size
    pub grid_size: (u32, u32),
    /// Widgets dalam layout
    pub widgets: Vec<WidgetConfig>,
    /// Background color
    pub background_color: String,
    /// Theme
    pub theme: String,
}

/// Dashboard utama
#[derive(Debug, Clone)]
pub struct Dashboard {
    /// Layout yang aktif
    active_layout: Arc<RwLock<DashboardLayout>>,
    /// Semua layout yang tersedia
    layouts: Arc<DashMap<String, DashboardLayout>>,
    /// Data real-time untuk widgets
    widget_data: Arc<DashMap<String, DashboardData>>,
    /// Cache untuk hasil render
    render_cache: Arc<DashMap<String, RenderedWidget>>,
    /// Konfigurasi dashboard
    config: DashboardConfig,
    /// Status update
    is_updating: Arc<RwLock<bool>>,
}

/// Konfigurasi untuk Dashboard
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DashboardConfig {
    /// Default layout name
    pub default_layout: String,
    /// Enable real-time updates
    pub real_time_enabled: bool,
    /// Cache timeout dalam detik
    pub cache_timeout: u64,
    /// Max widgets per layout
    pub max_widgets_per_layout: usize,
    /// Export formats yang didukung
    pub supported_export_formats: Vec<String>,
}

impl Default for DashboardConfig {
    fn default() -> Self {
        Self {
            default_layout: "default".to_string(),
            real_time_enabled: true,
            cache_timeout: 300, // 5 menit
            max_widgets_per_layout: 20,
            supported_export_formats: vec!["json".to_string(), "csv".to_string(), "png".to_string()],
        }
    }
}

/// Hasil render widget
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RenderedWidget {
    /// Widget name
    pub widget_name: String,
    /// Rendered data
    pub rendered_data: String,
    /// Render timestamp
    pub render_time: DateTime<Utc>,
    /// Cache expiry
    pub cache_expiry: DateTime<Utc>,
}

/// Export format untuk dashboard
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExportFormat {
    Json,
    Csv,
    Html,
    Png,
    Svg,
}

/// Dashboard builder untuk memudahkan konstruksi
pub struct DashboardBuilder {
    config: DashboardConfig,
    layouts: Vec<DashboardLayout>,
}

impl DashboardBuilder {
    /// Membuat builder baru
    pub fn new() -> Self {
        Self {
            config: DashboardConfig::default(),
            layouts: Vec::new(),
        }
    }

    /// Set custom config
    pub fn with_config(mut self, config: DashboardConfig) -> Self {
        self.config = config;
        self
    }

    /// Tambah layout
    pub fn add_layout(mut self, layout: DashboardLayout) -> Self {
        self.layouts.push(layout);
        self
    }

    /// Build dashboard
    pub fn build(self) -> Result<Dashboard, String> {
        let dashboard = Dashboard::new(self.config);

        // Tambahkan semua layout
        for layout in self.layouts {
            dashboard.add_layout(layout)?;
        }

        Ok(dashboard)
    }
}

impl Dashboard {
    /// Membuat dashboard baru
    pub fn new(config: DashboardConfig) -> Self {
        let default_layout = DashboardLayout {
            name: config.default_layout.clone(),
            grid_size: (12, 8),
            widgets: Vec::new(),
            background_color: "#ffffff".to_string(),
            theme: "light".to_string(),
        };

        Self {
            active_layout: Arc::new(RwLock::new(default_layout)),
            layouts: Arc::new(DashMap::new()),
            widget_data: Arc::new(DashMap::new()),
            render_cache: Arc::new(DashMap::new()),
            config,
            is_updating: Arc::new(RwLock::new(false)),
        }
    }

    /// Membuat dashboard dengan builder
    pub fn builder() -> DashboardBuilder {
        DashboardBuilder::new()
    }

    /// Menambahkan layout baru
    pub fn add_layout(&self, layout: DashboardLayout) -> Result<(), String> {
        if layout.widgets.len() > self.config.max_widgets_per_layout {
            return Err(format!(
                "Layout exceeds maximum widgets limit: {} > {}",
                layout.widgets.len(),
                self.config.max_widgets_per_layout
            ));
        }

        self.layouts.insert(layout.name.clone(), layout);
        Ok(())
    }

    /// Mengganti layout yang aktif
    pub fn switch_layout(&self, layout_name: &str) -> Result<(), String> {
        match self.layouts.get(layout_name) {
            Some(layout) => {
                let mut active_layout = self.active_layout.write().unwrap();
                *active_layout = layout.clone();
                self.clear_render_cache();
                Ok(())
            }
            None => Err(format!("Layout '{}' not found", layout_name)),
        }
    }

    /// Mendapatkan layout yang aktif
    pub fn get_active_layout(&self) -> DashboardLayout {
        self.active_layout.read().unwrap().clone()
    }

    /// Menambahkan widget ke layout aktif
    pub fn add_widget(&self, widget: WidgetConfig) -> Result<(), String> {
        let mut active_layout = self.active_layout.write().unwrap();
        
        if active_layout.widgets.len() >= self.config.max_widgets_per_layout {
            return Err("Maximum widgets limit reached".to_string());
        }

        active_layout.widgets.push(widget);
        Ok(())
    }

    /// Menghapus widget dari layout aktif
    pub fn remove_widget(&self, widget_name: &str) -> bool {
        let mut active_layout = self.active_layout.write().unwrap();
        active_layout
            .widgets
            .retain(|widget| widget.name != widget_name);
        
        self.widget_data.remove(widget_name);
        self.render_cache.remove(widget_name);
        true
    }

    /// Update data untuk widget tertentu
    pub fn update_widget_data(&self, widget_name: &str, data: DashboardData) -> Result<(), String> {
        if !self.widget_exists(widget_name) {
            return Err(format!("Widget '{}' not found", widget_name));
        }

        self.widget_data.insert(widget_name.to_string(), data);
        Ok(())
    }

    /// Mengecek apakah widget ada
    fn widget_exists(&self, widget_name: &str) -> bool {
        let active_layout = self.active_layout.read().unwrap();
        active_layout.widgets.iter().any(|widget| widget.name == widget_name)
    }

    /// Render widget tertentu
    pub fn render_widget(&self, widget_name: &str) -> Result<RenderedWidget, String> {
        // Cek cache dulu
        if let Some(cached) = self.render_cache.get(widget_name) {
            if cached.cache_expiry > Utc::now() {
                return Ok(cached.clone());
            }
        }

        // Ambil widget config
        let active_layout = self.active_layout.read().unwrap();
        let widget_config = active_layout
            .widgets
            .iter()
            .find(|w| w.name == widget_name)
            .ok_or_else(|| format!("Widget '{}' not found", widget_name))?;

        // Ambil data widget
        let widget_data = self.widget_data
            .get(widget_name)
            .map(|data| data.clone())
            .unwrap_or_else(|| widget_config.data.clone());

        // Render data (dalam implementasi nyata akan lebih kompleks)
        let rendered_data = self.render_chart_data(&widget_config.chart_type, &widget_data)?;

        let rendered_widget = RenderedWidget {
            widget_name: widget_name.to_string(),
            rendered_data,
            render_time: Utc::now(),
            cache_expiry: Utc::now() + chrono::Duration::seconds(self.config.cache_timeout as i64),
        };

        // Simpan ke cache
        self.render_cache.insert(widget_name.to_string(), rendered_widget.clone());

        Ok(rendered_widget)
    }

    /// Render data chart
    fn render_chart_data(&self, chart_type: &ChartType, data: &DashboardData) -> Result<String, String> {
        match (chart_type, data) {
            (ChartType::Line, DashboardData::TimeSeries(data)) => {
                Ok(format!("Line chart with {} data points", data.len()))
            }
            (ChartType::Bar, DashboardData::Category(data)) => {
                Ok(format!("Bar chart with {} categories", data.len()))
            }
            (ChartType::Pie, DashboardData::Category(data)) => {
                Ok(format!("Pie chart with {} slices", data.len()))
            }
            (ChartType::Scatter, DashboardData::Scatter(data)) => {
                Ok(format!("Scatter plot with {} points", data.len()))
            }
            (ChartType::Gauge, DashboardData::SingleValue(value)) => {
                Ok(format!("Gauge showing value: {}", value))
            }
            _ => Err("Incompatible chart type and data".to_string()),
        }
    }

    /// Render semua widget dalam layout aktif
    pub fn render_all_widgets(&self) -> Result<Vec<RenderedWidget>, String> {
        let active_layout = self.active_layout.read().unwrap();
        let mut rendered_widgets = Vec::new();

        for widget_config in &active_layout.widgets {
            match self.render_widget(&widget_config.name) {
                Ok(rendered) => rendered_widgets.push(rendered),
                Err(e) => eprintln!("Failed to render widget {}: {}", widget_config.name, e),
            }
        }

        Ok(rendered_widgets)
    }

    /// Update real-time data untuk semua widget
    pub fn update_real_time_data(&self) -> Result<(), String> {
        if !self.config.real_time_enabled {
            return Ok(());
        }

        let mut is_updating = self.is_updating.write().unwrap();
        if *is_updating {
            return Err("Real-time update already in progress".to_string());
        }
        *is_updating = true;

        let active_layout = self.active_layout.read().unwrap();
        
        for widget_config in &active_layout.widgets {
            if widget_config.real_time_enabled {
                // Update data (dalam implementasi nyata akan mengambil dari sumber data aktual)
                let new_data = self.generate_sample_data(&widget_config.chart_type);
                self.update_widget_data(&widget_config.name, new_data)?;
            }
        }

        *is_updating = false;
        Ok(())
    }

    /// Generate sample data untuk testing
    fn generate_sample_data(&self, chart_type: &ChartType) -> DashboardData {
        use chrono::Duration;

        match chart_type {
            ChartType::Line => {
                let now = Utc::now();
                let data: Vec<(DateTime<Utc>, f64)> = (0..10)
                    .map(|i| (now - Duration::seconds(i * 10), (i as f64 * 10.0) + rand::random::<f64>() * 10.0))
                    .collect();
                DashboardData::TimeSeries(data)
            }
            ChartType::Bar | ChartType::Pie => {
                let categories = vec!["A", "B", "C", "D"];
                let data: Vec<(String, f64)> = categories
                    .into_iter()
                    .map(|cat| (cat.to_string(), rand::random::<f64>() * 100.0))
                    .collect();
                DashboardData::Category(data)
            }
            ChartType::Scatter => {
                let data: Vec<(f64, f64)> = (0..20)
                    .map(|i| (i as f64, (i as f64 * 2.0) + rand::random::<f64>() * 5.0))
                    .collect();
                DashboardData::Scatter(data)
            }
            ChartType::Gauge => {
                DashboardData::SingleValue(rand::random::<f64>() * 100.0)
            }
            _ => DashboardData::SingleValue(0.0),
        }
    }

    /// Export dashboard ke format tertentu
    pub fn export_dashboard(&self, format: ExportFormat) -> Result<String, String> {
        let rendered_widgets = self.render_all_widgets()?;
        
        match format {
            ExportFormat::Json => {
                let json_data = serde_json::to_string_pretty(&rendered_widgets)
                    .map_err(|e| format!("JSON serialization error: {}", e))?;
                Ok(json_data)
            }
            ExportFormat::Csv => {
                let mut csv_data = String::from("Widget Name,Render Time,Data\n");
                for widget in rendered_widgets {
                    csv_data.push_str(&format!("{},{},{}\n", 
                        widget.widget_name, 
                        widget.render_time, 
                        widget.rendered_data
                    ));
                }
                Ok(csv_data)
            }
            ExportFormat::Html => {
                let html = self.generate_html_export(rendered_widgets)?;
                Ok(html)
            }
            _ => Err(format!("Export format not supported: {:?}", format)),
        }
    }

    /// Generate HTML export
    fn generate_html_export(&self, widgets: Vec<RenderedWidget>) -> Result<String, String> {
        let mut html = String::from(r#"
<!DOCTYPE html>
<html>
<head>
    <title>Performance Dashboard</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; }
        .widget { border: 1px solid #ccc; padding: 10px; margin: 10px; }
        .widget-title { font-weight: bold; margin-bottom: 5px; }
        .widget-data { font-size: 14px; }
        .widget-time { font-size: 12px; color: #666; }
    </style>
</head>
<body>
    <h1>Performance Dashboard</h1>
"#);

        for widget in widgets {
            html.push_str(&format!(r#"
    <div class="widget">
        <div class="widget-title">{}</div>
        <div class="widget-data">{}</div>
        <div class="widget-time">Rendered: {}</div>
    </div>
"#, widget.widget_name, widget.rendered_data, widget.render_time));
        }

        html.push_str("</body></html>");
        Ok(html)
    }

    /// Clear render cache
    fn clear_render_cache(&self) {
        self.render_cache.clear();
    }

    /// Mendapatkan statistik dashboard
    pub fn get_dashboard_stats(&self) -> DashboardStats {
        let active_layout = self.active_layout.read().unwrap();
        let widget_count = active_layout.widgets.len();
        let layout_count = self.layouts.len();

        DashboardStats {
            total_widgets: widget_count,
            total_layouts: layout_count,
            active_layout_name: active_layout.name.clone(),
            cache_entries: self.render_cache.len(),
            real_time_enabled: self.config.real_time_enabled,
        }
    }
}

/// Statistik dashboard
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DashboardStats {
    pub total_widgets: usize,
    pub total_layouts: usize,
    pub active_layout_name: String,
    pub cache_entries: usize,
    pub real_time_enabled: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dashboard_creation() {
        let dashboard = Dashboard::new(DashboardConfig::default());
        let stats = dashboard.get_dashboard_stats();
        
        assert_eq!(stats.total_widgets, 0);
        assert_eq!(stats.total_layouts, 0);
        assert_eq!(stats.active_layout_name, "default");
    }

    #[test]
    fn test_widget_management() {
        let dashboard = Dashboard::new(DashboardConfig::default());
        
        let widget = WidgetConfig {
            name: "test_widget".to_string(),
            chart_type: ChartType::Line,
            data: DashboardData::TimeSeries(vec![]),
            colors: vec!["#ff0000".to_string()],
            size: (400, 300),
            position: (0, 0),
            title: "Test Widget".to_string(),
            description: Some("Test description".to_string()),
            update_interval: 30,
            real_time_enabled: true,
        };

        let result = dashboard.add_widget(widget);
        assert!(result.is_ok());

        let stats = dashboard.get_dashboard_stats();
        assert_eq!(stats.total_widgets, 1);
    }

    #[test]
    fn test_chart_data_rendering() {
        let dashboard = Dashboard::new(DashboardConfig::default());
        
        let time_series_data = DashboardData::TimeSeries(vec![
            (Utc::now(), 50.0),
            (Utc::now(), 60.0),
        ]);

        let result = dashboard.render_chart_data(&ChartType::Line, &time_series_data);
        assert!(result.is_ok());
        assert!(result.unwrap().contains("Line chart"));

        let category_data = DashboardData::Category(vec![
            ("Category A".to_string(), 100.0),
            ("Category B".to_string(), 200.0),
        ]);

        let result = dashboard.render_chart_data(&ChartType::Bar, &category_data);
        assert!(result.is_ok());
        assert!(result.unwrap().contains("Bar chart"));
    }

    #[test]
    fn test_dashboard_export() {
        let dashboard = Dashboard::new(DashboardConfig::default());
        
        // Tambahkan widget untuk testing
        let widget = WidgetConfig {
            name: "export_test".to_string(),
            chart_type: ChartType::Line,
            data: DashboardData::TimeSeries(vec![]),
            colors: vec![],
            size: (400, 300),
            position: (0, 0),
            title: "Export Test".to_string(),
            description: None,
            update_interval: 30,
            real_time_enabled: false,
        };

        dashboard.add_widget(widget).unwrap();
        
        // Test JSON export
        let json_export = dashboard.export_dashboard(ExportFormat::Json);
        assert!(json_export.is_ok());
        
        // Test CSV export
        let csv_export = dashboard.export_dashboard(ExportFormat::Csv);
        assert!(csv_export.is_ok());
        
        // Test HTML export
        let html_export = dashboard.export_dashboard(ExportFormat::Html);
        assert!(html_export.is_ok());
    }

    #[test]
    fn test_dashboard_builder() {
        let layout = DashboardLayout {
            name: "test_layout".to_string(),
            grid_size: (12, 8),
            widgets: vec![],
            background_color: "#ffffff".to_string(),
            theme: "light".to_string(),
        };

        let dashboard = Dashboard::builder()
            .add_layout(layout)
            .build();

        assert!(dashboard.is_ok());
    }
}