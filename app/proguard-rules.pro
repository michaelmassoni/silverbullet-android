# R8 shrinks release builds (unused code and resources). Names are kept so crash reports stay
# readable; the code is open source anyway, so obfuscation would gain nothing.
-dontobfuscate

# Recreated by class name after process death.
-keep class io.github.michaelmassoni.silverbullet.SettingsActivity$SettingsFragment { <init>(); }
