package jc121f1.model.instance.dao;

import jc121f1.model.instance.InstanceState;

import java.time.Instant;

public class Instance {
    private String name;

    private int cpu;

    private int memory;

    private String id;

    private InstanceState state;

    private Instant createdAt;
}
