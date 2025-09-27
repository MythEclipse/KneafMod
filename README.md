# Kneaf Core

A server-side performance optimization mod for Minecraft 1.21 using Rust for high-performance computations.

## Features

- **Dynamic Entity Ticking Throttling**: Reduces tick rates for distant entities to improve server performance
- **Item Stack Merging**: Automatically merges duplicate item entities to reduce entity count
- **AI Optimization**: Simplifies pathfinding for distant mobs
- **Rust Integration**: Uses Rust libraries for efficient data processing
- **Server-Side Only**: Compatible with vanilla clients

## Installation

1. Download the mod jar from [Modrinth](https://modrinth.com/mod/kneaf-core) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/kneaf-core)
2. Place the jar file in your server's `mods` folder
3. Start the server

## Configuration

Edit `config/rustperf.toml` to customize:
- Throttling distances and rates
- Item merging limits
- AI optimization settings

## Compatibility

- **Server-Side**: Fully compatible with vanilla clients
- **Large Modpacks**: Tested with 50+ mods
- **Incompatible with**: Lithium, Starlight, FerriteCore (conflicting optimizations)

## Commands

- `/rustperf status`: View real-time performance metrics

## Building from Source

Requires Java 21 and Rust.

```bash
./gradlew build
```

This will compile the Rust library and package it into the mod jar.

## License

MIT License

## Support

Report issues at [GitHub Issues](https://github.com/yourusername/kneafmod/issues)
