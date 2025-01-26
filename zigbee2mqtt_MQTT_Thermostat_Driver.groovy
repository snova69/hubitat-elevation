metadata {
    definition (name: "MQTT Thermostat Driver for Zigbee2MQTT topics", namespace: "anemirovsky", author: "Alex Nemirovsky", importUrl: "") {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "Actuator"
        capability "Initialize"

        attribute "mqttConnected", "string"

        command "setHeatingSetpoint", [[name: "Setpoint*", type: "NUMBER"]]
        command "setCoolingSetpoint", [[name: "Setpoint*", type: "NUMBER"]]
    }

    preferences {
        input name: "mqttBroker", type: "string", title: "MQTT Broker Address", required: true
        input name: "deviceTopic", type: "string", title: "Device Topic (e.g., zigbee2mqtt/thermostat)" , required: true
        input name: "username", type: "string", title: "MQTT Username (optional)", required: false
        input name: "password", type: "password", title: "MQTT Password (optional)", required: false
        input name: "logging", type: "bool", title: "Enable Debug Logging", defaultValue: true
    }
} 

def convertMyTemp(value) {
    if (value != null) {
        def celsius = new BigDecimal(value).setScale(1, BigDecimal.ROUND_HALF_UP)
        if (convertMyTempScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

def convertMyTempScale() {
    return "${location.temperatureScale}"
}

void installed() {
    initialize()
}

void updated() {
    initialize()
}

void initialize() {
    if (logging) log.debug "Initializing MQTT Thermostat Driver..."

    interfaces.mqtt.disconnect()

    try {
        interfaces.mqtt.connect("tcp://" + mqttBroker, "hubitat-${device.id}", username, password)
        pauseExecution(1000)
        interfaces.mqtt.subscribe("${deviceTopic}")
        sendEvent(name: "mqttConnected", value: "true")
    } catch (e) {
        log.error "Failed to connect to MQTT broker: ${e.message}"
        sendEvent(name: "mqttConnected", value: "false")
    }
}

void uninstalled() {
    if (logging) log.debug "Disconnecting from MQTT broker..."
    interfaces.mqtt.disconnect()
}

void parse(String message) {
    if (logging) log.debug "Received message: ${message}"

    def payload = interfaces.mqtt.parseMessage(message)
    def json = parseJson(payload.payload)
    def local_temperature = convertMyTemp(json.local_temperature)
    def occupied_heating_setpoint = convertMyTemp(json.occupied_heating_setpoint)
    def occupied_cooling_setpoint = convertMyTemp(json.occupied_cooling_setpoint)
    if (json.local_temperature) {
        sendEvent(name: "temperature", value: local_temperature, unit: getTemperatureScale())
    }

    if (json.humidity) {
        sendEvent(name: "humidity", value: json.humidity, unit: "%")
    }

    if (json.occupied_heating_setpoint) {
        sendEvent(name: "heatingSetpoint", value: occupied_heating_setpoint, unit: getTemperatureScale())
    }

    if (json.occupied_cooling_setpoint) {
        sendEvent(name: "coolingSetpoint", value: occupied_cooling_setpoint, unit: getTemperatureScale())
    }

    if (json.system_mode) {
        sendEvent(name: "thermostatMode", value: json.system_mode)
    }

    if (json.running_state) {
        switch(json.running_state)
        {
            case "idle":
            sendEvent(name: "thermostatOperatingState", value: "idle")
            break

            case "heat":
            sendEvent(name: "thermostatOperatingState", value: "heating")
            break
            
            case "cool":
            sendEvent(name: "thermostatOperatingState", value: "cooling")
            break
        }
        
    }
}

void setHeatingSetpoint(setpoint) {
    def temperatureScale = getTemperatureScale()
    def degrees = new BigDecimal(setpoint).setScale(1, BigDecimal.ROUND_HALF_UP)   
    def celsius = (temperatureScale == "C") ? setpoint as Float : (fahrenheitToCelsius(degrees) as Float).round(2)

    publishMqttMessage(["occupied_heating_setpoint": celsius])
}

void setCoolingSetpoint(setpoint) {
    def temperatureScale = getTemperatureScale()
    def degrees = new BigDecimal(setpoint).setScale(1, BigDecimal.ROUND_HALF_UP)  
 
    def celsius = (temperatureScale == "C") ? setpoint as Float : (fahrenheitToCelsius(degrees) as Float).round(2)

    publishMqttMessage(["occupied_cooling_setpoint": celsius])
}

void setThermostatMode(String mode) {
    if (logging) log.debug "Setting thermostat mode to ${mode}"
    publishMqttMessage(["system_mode": mode])
}

void publishMqttMessage(Map payload) {
    def json = new groovy.json.JsonBuilder(payload).toString()
    interfaces.mqtt.publish("${deviceTopic}/set", json)
}


