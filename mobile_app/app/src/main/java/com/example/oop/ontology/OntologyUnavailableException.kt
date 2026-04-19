package com.example.oop.ontology

class OntologyUnavailableException(source: String) :
    IllegalStateException("Ontology data unavailable: $source")
