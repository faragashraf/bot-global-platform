using BotGlobal.Communication.Contracts.Calls;
using BotGlobal.Communication.Contracts.Messaging;
using BotGlobal.Communication.Contracts.Presence;

namespace BotGlobal.Communication.Hubs;

public interface ICommunicationClient
{
    Task MessageReceived(MessageEnvelope message);
    Task MessageDelivered(MessageDeliveredEvent receipt);
    Task MessageRead(MessageReadEvent receipt);
    Task TypingChanged(TypingChangedEvent typing);
    Task PresenceChanged(PresenceChangedEvent presence);
    Task IncomingCall(IncomingCallEvent call);
    Task CallAccepted(CallAcceptedEvent call);
    Task CallRejected(CallRejectedEvent call);
    Task CallEnded(CallEndedEvent call);
    Task WebRtcOffer(WebRtcOfferEvent offer);
    Task WebRtcAnswer(WebRtcAnswerEvent answer);
    Task IceCandidate(IceCandidateEvent candidate);
}
