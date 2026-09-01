package jc121f1.wbs.exceptions;

import io.javalin.http.Context;


import javax.inject.Inject;
import java.util.Map;
import java.util.function.Function;

public class MiniCloudExceptionMapper {

    private final Map<Class<? extends RuntimeException>,
            Function<String, CustomerFacingError>> exceptionMap = Map.of(
            jc121f1.services.instance.exceptions.ValidationException.class, ValidationError::new,
            jc121f1.services.instance.exceptions.ConflictException.class, ConflictError::new,
            jc121f1.services.instance.exceptions.ResourceNotFoundException.class, ResourceNotFoundError::new
    );

    @Inject
    public MiniCloudExceptionMapper() {
    }

    public void mapException(Exception e, Context ctx) {
        CustomerFacingError translated = translate(e);
        ctx.status(translated.getStatusCode());
        ctx.json(translated);
    }

    private CustomerFacingError translate(Exception e) {
        Function<String, CustomerFacingError> factory = exceptionMap.get(e.getClass());

        return factory == null
                ? new InternalServerError("We cannot process your request right now. Try again later.")
                : factory.apply(e.getMessage());
    }
}
