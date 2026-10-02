using BotGlobal.Calling.Application;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Calling;

public sealed class NqrbBlockServiceTests
{
    [Fact]
    public async Task Block_is_private_to_owner_and_can_be_reversed_without_removing_a_contact()
    {
        await using var db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
            .UseInMemoryDatabase($"nqrb-block-{Guid.NewGuid():N}").Options);
        var owner = Guid.NewGuid();
        var other = Guid.NewGuid();
        var service = new NqrbBlockService(db, new Accounts(other), TimeProvider.System);

        Assert.False(await service.BlockAsync(owner, owner, default));
        Assert.True(await service.BlockAsync(owner, other, default));
        Assert.True(await service.BlockAsync(owner, other, default));
        Assert.True(await service.IsBlockedAsync(owner, other, default));
        Assert.False(await service.IsBlockedAsync(other, owner, default));
        Assert.Equal(other, Assert.Single(await service.ListAsync(owner, default)).MembershipId);
        Assert.Single(db.NqrbBlockedAccounts);

        await service.UnblockAsync(owner, other, default);
        Assert.False(await service.IsBlockedAsync(owner, other, default));
        Assert.Empty(await service.ListAsync(owner, default));
    }

    private sealed class Accounts(Guid other) : ICallingAccountDirectory
    {
        public Task<CallingAccountDescriptor?> FindActiveNonGuestAsync(
            string applicationKey, Guid membershipId, CancellationToken cancellationToken) =>
            Task.FromResult<CallingAccountDescriptor?>(applicationKey == BotGlobalApplications.Nqrb && membershipId == other
                ? new(other, "Other account") : null);

        public Task<IReadOnlyList<CallingAccountDescriptor>> FindActiveNonGuestAsync(
            string applicationKey, IReadOnlyCollection<Guid> membershipIds, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CallingAccountDescriptor>>(
                applicationKey == BotGlobalApplications.Nqrb && membershipIds.Contains(other)
                    ? [new CallingAccountDescriptor(other, "Other account")] : []);

        public Task<CallingAccountSearchPage> SearchActiveNonGuestsAsync(
            string applicationKey, Guid currentMembershipId, string query, int page, int pageSize,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }
}
