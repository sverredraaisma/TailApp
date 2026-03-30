# Tail app
the tail app is a companion android application for the Tail firmware project. all the
documentation for the tail firmware can be found at "C:\Users\Sverr\CLionProjects\TailFirmware\docs"
The app works to configure, control and assist the tail. It does this with at least the following features:

## Features
- screen with nearby bluetooth devices
  - should prioritize any device with the name "Tail controller"
- user can click on a device to connect to it
- device screen shows general information about the device and the current configuration
  - shows the status of subsystems (servo's, bluetooth, leds, IMU's, I2C)
  - shows the currently selected movement profile
  - shows the current position of all the servo's
  - shows the currently selected lighting effect layers and combination methods
  - shows the total amount of Leds being driven
- device screen has a button in the top that toggles the FFT stream on or off to the device
- device screen has the following subscreens
  - led config
    - user can see and change current led matrix config
    - user can see the currently selected layers and
      - make changes to the values of parameters
      - change the combination method
      - remove the layer
      - add an extra layer
    - for the image effect all required functionality to upload an image must be available
  - motion config
    - see currently selected motion pattern and 
      - change the selected profile
      - change the parameters of the profile
    - see and change current settings for each servo
    - calibrate zero
  - Audio config (settings for how the FFT stream gets constructed)
    - settings for the amount of bins in the FFT
    - volume normalization speed settings (the app tries to normalize the gain of the mic for the FFT)
    - frequency range start and end for bins

To make these features refer to the documentation of the firmware.

an important note is that to safe data in the firmware, the companion app must be aware of all the 
effect names and properties. these are not broadcasted. the app should update it's displays when it 
changes settings so they stay accurate.