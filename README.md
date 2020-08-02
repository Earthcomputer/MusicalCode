# MusicalCode

Configure MusicalCode to listen for changes in Minecraft methods. Run it every update, and it will tell you which methods changed.

## Configuration

MusicalCode is configured in a single text file format. Currently, the following are supported:
```
# Line comment
net/minecraft/client/MinecraftClient/*   # Listens for changes in all members inside Screen
net/minecraft/block/GrassPathBlock.SHAPE : Lnet/minecraft/util/shape/VoxelShape;   # Listens for changes in this specific field
net/minecraft/client/gui/hud/InGameHud.renderCrosshair (Lnet/minecraft/client/util/math/MatrixStack;)V   # Listens for changes in this specific method
```
More types of listeners may be added in the future.

To quickly enter these values, it may be helpful to use the "Copy Mixin Target Reference" feature of Minecraft Dev,
although be aware of the format differences. All whitespace is optional, besides the new lines.

## Okay, so how do I use it?

### Gradle

You can install MusicalCode as a Gradle plugin in your mod.

This Gradle plugin requires Java 11 for me, and I have no clue why. If you figure out why, please let me know. 

Add the maven repository in `settings.gradle` like so:
```groovy
pluginManagement {
    repositories {
        jcenter()
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        // Added:
        maven {
            name = 'Earthcomputer Tools'
            url = 'https://dl.bintray.com/earthcomputer/util'
        }
        // End added
        gradlePluginPortal()
    }
}
```

Then add the following to `build.gradle`:
```groovy
plugins {
    id 'musical-code' version '1.0'
}
// ...
musicalCode {
    config 'musical-config.txt'
}
```

Then run the task like so:
```
gradlew musicalCodeTask --from <old-mc-version>
```

Additional settings are as follows:
```groovy
musicalCode {
    from 'from-version' // overridden by --from on the command line
    to 'to-version' // overridden by --to on the command line
    config 'config.txt'
    output 'musical-code-output.txt' // where to print output, defaults to stdout
}
```

### Standalone

MusicalCode can be run as a standalone command line Java program. You can also download the standalone version
from the [maven](https://dl.bintray.com/earthcomputer/util/musical-code/musical-code.gradle.plugin).

To get its usage, run:
```
java -jar musical-code-standalone.jar --help
```
