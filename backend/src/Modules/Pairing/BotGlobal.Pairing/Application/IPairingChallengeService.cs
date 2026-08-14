using BotGlobal.Pairing.Contracts;

namespace BotGlobal.Pairing.Application;

public enum ClaimPairingChallengeOutcome
{
    Completed = 1,
    InvalidExpiredOrUsed = 2
}

public sealed record ClaimPairingChallengeResult(
    ClaimPairingChallengeOutcome Outcome,
    ClaimPairingChallengeResponse? Response);

public interface IPairingChallengeService
{
    Task<CreatePairingChallengeResponse> CreateAsync(
        Guid platformClientId,
        CreatePairingChallengeRequest request,
        CancellationToken cancellationToken = default);

    Task<PairingChallengeStatusResponse?> GetStatusAsync(
        Guid platformClientId,
        Guid challengeId,
        CancellationToken cancellationToken = default);

    Task<ClaimPairingChallengeResult> ClaimAsync(
        ClaimPairingChallengeRequest request,
        CancellationToken cancellationToken = default);
}
