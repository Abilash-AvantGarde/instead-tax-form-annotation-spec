package com.instead.annotation.model;

/** One mutually-exclusive option within a {@code radio-group} field. */
public class RadioOption {
    public String id;
    public Box box;
    public Object matchValue;
    public String mark = "X";
}
