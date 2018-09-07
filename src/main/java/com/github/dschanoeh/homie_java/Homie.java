package com.github.dschanoeh.homie_java;

import java.time.Duration;
import java.time.ZonedDateTime;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Homie {

    private final static Logger LOGGER = Logger.getLogger(Homie.class.getName());

    private static final String HOMIE_CONVENTION = "3.0.0";
    private static final String IMPLEMENTATION = "java";

    public static enum State {
        INIT, READY, DISCONNECTED, SLEEPING, LOST, ALERT
    }

    private Configuration configuration = new Configuration();
    private final String firmwareName;
    private final String firmwareVersion;
    private MqttClient client;
    private State state = State.INIT;
    private State previousState = State.DISCONNECTED;
    private Thread stateMachineThread;
    private final ZonedDateTime bootTime = ZonedDateTime.now();
    private boolean shutdownRequest = false;
    private Timer statsTimer;
    private Function<Void, String> cpuTemperatureFunction;
    private Function<Void, String> cpuLoadFunction;

    HashMap<String, Node> nodes = new HashMap<>();

    public State getState() {
        return state;
    }

    /**
     * Allows the user to supply a CPU temperature function that will be called
     * when the stats are collected.
     *
     * @param cpuTemperatureFunction
     */
    public void setCpuTemperatureFunction(Function<Void, String> cpuTemperatureFunction) {
        this.cpuTemperatureFunction = cpuTemperatureFunction;
    }

    /**
     * Allows the user to supply a CPU load function that will be called when
     * the stats are collected.
     *
     * @param cpuLoadFunction
     */
    public void setCpuLoadFunction(Function<Void, String> cpuLoadFunction) {
        this.cpuLoadFunction = cpuLoadFunction;
    }

    private final Runnable stateMachine = () -> {
        try {
            while (!shutdownRequest) {
                switch (state) {
                    case INIT:
                        if (previousState != State.INIT) {
                            LOGGER.log(Level.INFO, "--> init");
                            previousState = State.INIT;
                        }
                        if (connect()) {
                            state = State.READY;
                        } else {
                            state = State.DISCONNECTED;
                        }
                        break;
                    case READY:
                        if (previousState != State.READY) {
                            LOGGER.log(Level.INFO, "--> ready");
                            onConnect();
                            previousState = State.READY;
                        }
                        if (!client.isConnected()) {
                            state = State.DISCONNECTED;
                        }
                        break;
                    case DISCONNECTED:
                        if (previousState != State.DISCONNECTED) {
                            LOGGER.log(Level.INFO, "--> disconnected");
                            previousState = State.DISCONNECTED;
                        }
                        Thread.sleep(configuration.getDisconnectRetry());
                        if (connect()) {
                            state = State.READY;
                        }
                    default:
                        break;
                }

                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "State machine interrupted", e);
        }
    };

    /**
     * Initialize a new Homie instance with the configuration and firmware name
     * and version.
     */
    public Homie(Configuration c, String firmwareName, String firmwareVersion) {
        this.configuration = c;
        this.firmwareName = firmwareName;
        this.firmwareVersion = firmwareVersion;
    }

    /**
     * Calling setup causes the state machine to start and Homie to connect.
     */
    public void setup() {
        stateMachineThread = new Thread(stateMachine);
        stateMachineThread.start();
    }

    private boolean connect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }

            client = new MqttClient(configuration.getBrokerUrl(), configuration.getDeviceID());
            client.connect();

            if (statsTimer != null) {
                statsTimer.cancel();
            }

            statsTimer = new Timer();

            TimerTask statsTask = new TimerTask() {
                @Override
                public void run() {
                    sendStats();
                }
            };

            statsTimer.scheduleAtFixedRate(statsTask, 0, configuration.getStatsInterval());
            return true;
        } catch (MqttException e) {
            LOGGER.log(Level.SEVERE, "Couldn't connect", e);
            return false;
        }
    }

    private void sendAttributes() {
        publish("$homie", HOMIE_CONVENTION, true);
        publish("$name", configuration.getDeviceID(), true);
        publish("$state", state.toString().toLowerCase(), true);
        publish("$implementation", IMPLEMENTATION, true);
        publish("$stats/interval", Integer.toString(configuration.getStatsInterval()), true);
        publish("$fw/name", firmwareName, true);
        publish("$fw/version", firmwareVersion, true);
    }

    private boolean sendStats() {
        long uptime = Duration.between(bootTime, ZonedDateTime.now()).getSeconds();
        publish("$stats/uptime", Long.toString(uptime), true);

        if (cpuTemperatureFunction != null) {
            publish("$stats/cputemp", cpuTemperatureFunction.apply(null), true);
        }

        if (cpuLoadFunction != null) {
            publish("$stats/cpuload", cpuLoadFunction.apply(null), true);
        }

        return true;
    }

    /**
     * Publish an MQTT message.
     */
    public void publish(String topic, String payload, Boolean retained) {
        if (state == State.READY) {
            MqttMessage message = new MqttMessage();
            message.setRetained(retained);
            message.setPayload(payload.getBytes());
            try {
                client.publish(buildPath(topic), message);
            } catch (MqttException e) {
                LOGGER.log(Level.SEVERE, "Couldn't publish message", e);
            }
        } else {
            LOGGER.log(Level.WARNING, "Couldn't publish message - not connected.");
        }
    }

    private void onConnect() {
        sendAttributes();
        publishNodes();
    }

    private void publishNodes() {
        String n = nodes.keySet().stream().collect(Collectors.joining(","));
        publish("$nodes", n, true);

        nodes.entrySet().forEach(i -> i.getValue().onConnect());
    }

    private String buildPath(String attribute) {
        return configuration.getDeviceTopic() + "/" + configuration.getDeviceID() + "/" + attribute;
    }

    public void shutdown() {
        LOGGER.log(Level.INFO, "Shutdown request received");
        shutdownRequest = true;
        try {
            stateMachineThread.join();
        } catch (InterruptedException e) {
            LOGGER.log(Level.INFO, "Interrupted", e);
        }
        disconnect();
        LOGGER.log(Level.INFO, "Terminating");
    }

    private void disconnect() {
        try {
            client.disconnect();
        } catch (MqttException e) {
            LOGGER.log(Level.INFO, "Failed to disconnect", e);
        }
    }

    /**
     * Generates and registers a new node within Homie.
     */
    public Node createNode(String name, String type) {
        if (nodes.containsKey(name)) {
            return nodes.get(name);
        } else {
            Node n = new Node(this, name, type);
            nodes.put(name, n);
            return n;
        }
    }

}
