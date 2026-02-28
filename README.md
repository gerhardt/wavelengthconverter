# light & matter - Wavelength Converter

Android app for converting between different units of electromagnetic radiation and finding related spectroscopic information.

## Features

### Multi-Unit Input
Enter values in any of these units:
- **nm** (nanometers - wavelength)
- **GHz** (gigahertz - frequency)
- **cm⁻¹** (wavenumber)
- **eV** (electron volts - energy)
- **kJ/mol** (kilojoules per mole - energy)

All other units are calculated along in real-time!

<img width="200" height="922" alt="image" alight="right" src="https://github.com/user-attachments/assets/7af3ee27-5fdc-4f2d-885a-8fd4ca2faef8" />

### Visual Color Display
See the actual color of the wavelength displayed as a colored bar

### Comprehensive Database
- Laser lines (Nd:YAG, HeNe, Argon-Ion, etc.)
- Absorption/Emission maxima of dyes and molecules
- Fraunhofer lines
- Semiconductor bandgaps
- Laser ranges
- Lines of spectral lamps (for calibration)

## Usage

1. Launch "light & matter" app
2. Enter a value (e.g., `532`)
3. Change the value by sliding right/left on the value
4. Change the value by slidung right/left on the colorbar
5. Scroll up/down to see related wavelengths from the database (changes also the value above)

## Compiling
1. export ANDROID_HOME=$HOME/Android/Sdk
2. rm -rf .gradle build app/build
3. ./gradlew clean
4. ./gradlew assembleDebug
5. adb install ./app/build/outputs/apk/debug/app-debug.apk

## License

Based on the wavelength calculator from Ilja Gerhardt / light & matter group
Website: https://gerhardt.ch/
