To handle an Android head unit with two Bluetooth modules or two Bluetooth MAC addresses, you need to manage connections to both Bluetooth devices separately. Here is an approach to handle this scenario:  
Identify and store both Bluetooth MAC addresses.
Create separate connection logic for each Bluetooth device.
Manage the connections and handle reconnections if needed.
Here is an example of how you can modify your BluetoothServiceDummy to handle two Bluetooth devices:
You can coppy content of BluetoothServiceDummy.kt and create a new file named BluetoothService.kt and make sure class name changed to BluetoothService