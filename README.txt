AutoMine (Fabric, Minecraft 1.21.11, client-side)

BUILD THE JAR (needs JDK 21 + Gradle 9.x installed)
  Run build.bat (Windows) or ./build.sh (Mac/Linux)
  -> jar appears at build/libs/automine-1.0.0.jar
  Or: open this folder in IntelliJ IDEA and run the "build" Gradle task.
  Or: push to GitHub; the included Actions workflow builds the jar for you (Artifacts).

INSTALL
  Fabric Loader 0.18.1+ and Fabric API for 1.21.11, then put the jar in .minecraft/mods

COMMANDS
  /automine <block> [amount]     e.g. /automine diamond_ore 10
  /automine stop
