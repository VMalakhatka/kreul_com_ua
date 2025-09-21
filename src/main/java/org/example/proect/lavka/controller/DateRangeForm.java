package org.example.proect.lavka.controller;

import jakarta.validation.constraints.Past;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;
@Data
@AllArgsConstructor
public class DateRangeForm {

    private String start;

    private String end;

}