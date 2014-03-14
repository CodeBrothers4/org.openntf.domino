package org.openntf.domino.formula;

import org.openntf.domino.DateTime;

public interface Formatter {

	DateTime parseDate(String image) throws java.text.ParseException;

	Number parseNumber(String el) throws java.text.ParseException;;

}