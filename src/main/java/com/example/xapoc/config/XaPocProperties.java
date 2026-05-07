package com.example.xapoc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xa-poc")
public class XaPocProperties {

    private int maxConcurrentTransactions = 80;
    private final FaultInjection faultInjection = new FaultInjection();
    private final Consumer consumer = new Consumer();
    private final Jms jms = new Jms();

    public int getMaxConcurrentTransactions() { return maxConcurrentTransactions; }
    public void setMaxConcurrentTransactions(int v) { this.maxConcurrentTransactions = v; }

    public FaultInjection getFaultInjection() { return faultInjection; }
    public Consumer getConsumer() { return consumer; }
    public Jms getJms() { return jms; }

    public static class FaultInjection {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }

    public static class Consumer {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }

    public static class Jms {
        private String mode = "xa";
        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }
    }
}
