using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Endpoints;
using BotGlobal.Communication.Hubs;
using Microsoft.AspNetCore.Authorization;

namespace BotGlobal.UnitTests.Communication;

public sealed class CommunicationDeliveryContractTests
{
    [Fact]
    public void Typed_client_exposes_realtime_test_event()
    {
        var method = typeof(ICommunicationClient)
            .GetMethod("RealtimeTestMessageReceived");

        Assert.NotNull(method);

        Assert.Equal(
            typeof(Task),
            method!.ReturnType);
    }

    [Fact]
    public void Test_request_does_not_accept_sender_identity()
    {
        var properties =
            typeof(SendRealtimeTestMessageRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Contains("TargetUserId", properties);
        Assert.Contains("Text", properties);

        Assert.DoesNotContain(
            properties,
            property => property.Contains(
                "Sender",
                StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Communication_hub_remains_authorized()
    {
        Assert.NotNull(
            typeof(CommunicationHub)
                .GetCustomAttributes(
                    typeof(AuthorizeAttribute),
                    inherit: true)
                .SingleOrDefault());
    }

    [Fact]
    public void Realtime_test_message_contains_delivery_identity()
    {
        var message = new RealtimeTestMessage(
            "delivery-1",
            "sender-1",
            "target-1",
            "hello",
            DateTimeOffset.UtcNow);

        Assert.Equal("delivery-1", message.DeliveryId);
        Assert.Equal("sender-1", message.SenderUserId);
        Assert.Equal("target-1", message.TargetUserId);
        Assert.Equal("hello", message.Text);
    }
}
