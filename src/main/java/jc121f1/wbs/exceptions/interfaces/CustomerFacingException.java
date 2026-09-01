package jc121f1.wbs.exceptions.interfaces;

public interface CustomerFacingException {

    default CustomerFacingException defaultException() {
        return this;
    }

    int getStatusCode();
}
