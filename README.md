# Create Pipes n Physics

A Create mod addon for pipes and physics. Built with **Java** and **NeoForge 1.21.1**.

## What's Included

- **NeoForge 1.21.1** with Create 6.0.10 dependency
- **Create Registrate** — Create's registration system, pre-configured
- **Ponder & Flywheel** — Create's rendering and documentation libraries
- **JEI** — recipe viewer integration (optional, compile-only)
- **Mixin support** — pre-configured mixins
- **GitHub Actions** — automatic builds on push/PR
- **Gradle 8.10** with configuration cache enabled

## Getting Started

### 1. Use this template

Click **"Use this template"** on GitHub, or clone and rename.

### 2. Configure your mod

Edit `gradle.properties`:

```properties
mod_id=yourmod
mod_name=Your Mod Name
mod_version=0.1.0
mod_group_id=com.yourname.yourmod
mod_authors=YourName
mod_description=Your mod description.
mod_license=MIT
```

### 3. Rename packages

1. Rename `src/main/java/com/example/examplemod/` to match your `mod_group_id`
2. Update `ExampleMod.java` — change `ID` to your `mod_id`
3. Rename `src/main/resources/examplemod.mixins.json` to `{mod_id}.mixins.json`
4. Update the package path inside the mixins JSON

### 4. Build and run

```bash
./gradlew build          # Build the mod
./gradlew runClient      # Launch Minecraft with your mod
./gradlew runServer      # Launch a dedicated server
./gradlew runData        # Run data generators
```

## License

This template is provided under the [MIT License](LICENSE). Your mod built from this template can use any license you choose.
