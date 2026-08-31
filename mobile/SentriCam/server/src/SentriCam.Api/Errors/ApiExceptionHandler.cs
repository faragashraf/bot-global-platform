using FluentValidation;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Common;
using SentriCam.Domain.Common;

namespace SentriCam.Api.Errors;

public sealed class ApiExceptionHandler(
    ILogger<ApiExceptionHandler> logger,
    IProblemDetailsService problemDetailsService) : IExceptionHandler
{
    private static readonly Action<ILogger, string, Exception?> LogUnhandledError =
        LoggerMessage.Define<string>(
            LogLevel.Error,
            new EventId(5000, nameof(LogUnhandledError)),
            "Unhandled server error. Trace id: {TraceId}");

    private static readonly Action<ILogger, int?, Exception?> LogRequestFailure =
        LoggerMessage.Define<int?>(
            LogLevel.Information,
            new EventId(4000, nameof(LogRequestFailure)),
            "Request failed with status {StatusCode}.");

    public async ValueTask<bool> TryHandleAsync(
        HttpContext httpContext,
        Exception exception,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(httpContext);
        ArgumentNullException.ThrowIfNull(exception);

        var problem = MapProblem(exception, httpContext.TraceIdentifier);
        if (problem.Status >= StatusCodes.Status500InternalServerError)
        {
            LogUnhandledError(logger, httpContext.TraceIdentifier, exception);
        }
        else
        {
            LogRequestFailure(logger, problem.Status, exception);
        }

        httpContext.Response.StatusCode = problem.Status ?? StatusCodes.Status500InternalServerError;
        return await problemDetailsService.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = httpContext,
            ProblemDetails = problem,
            Exception = exception,
        });
    }

    private static ProblemDetails MapProblem(Exception exception, string traceId)
    {
        var problem = exception switch
        {
            ValidationException validationException => ValidationProblem(validationException),
            ResourceNotFoundException => CreateProblem(
                StatusCodes.Status404NotFound,
                "Resource not found",
                exception.Message),
            AccessDeniedException => CreateProblem(
                StatusCodes.Status403Forbidden,
                "Access denied",
                exception.Message),
            ResourceConflictException => CreateProblem(
                StatusCodes.Status409Conflict,
                "Resource conflict",
                exception.Message),
            PayloadTooLargeException => CreateProblem(
                StatusCodes.Status413PayloadTooLarge,
                "Payload too large",
                exception.Message),
            DomainValidationException => CreateProblem(
                StatusCodes.Status422UnprocessableEntity,
                "Business rule violation",
                exception.Message),
            DbUpdateConcurrencyException => CreateProblem(
                StatusCodes.Status409Conflict,
                "Concurrency conflict",
                "The resource changed while the request was being processed."),
            DbUpdateException => CreateProblem(
                StatusCodes.Status409Conflict,
                "Persistence conflict",
                "The request conflicts with the current persisted state."),
            _ => CreateProblem(
                StatusCodes.Status500InternalServerError,
                "Server error",
                "An unexpected error occurred."),
        };

        problem.Extensions["traceId"] = traceId;
        return problem;
    }

    private static ProblemDetails ValidationProblem(ValidationException exception)
    {
        var problem = CreateProblem(
            StatusCodes.Status400BadRequest,
            "Validation failed",
            "One or more validation errors occurred.");
        problem.Extensions["errors"] = exception.Errors
            .GroupBy(error => error.PropertyName)
            .ToDictionary(
                group => group.Key,
                group => group.Select(error => error.ErrorMessage).Distinct().ToArray());
        return problem;
    }

    private static ProblemDetails CreateProblem(int status, string title, string detail) => new()
    {
        Status = status,
        Title = title,
        Detail = detail,
        Type = $"https://httpstatuses.com/{status}",
    };
}
