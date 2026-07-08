# Explorer report — Request-arrival topology & thread handoffs (2026-07-05)

Tree: trunk / 6.0-alpha (Accord + TCM present). Netty 4.1.130.Final.

## 1. CQL native transport inbound

- Server: transport/Server.java; useEpoll (Server.java:80 → NativeTransportService.java:136-144,
  flag `cassandra.native.epoll.enabled`); worker group no-arg Epoll/NioEventLoopGroup
  (Server.java:108-111; NativeTransportService.java:67-74, injected :82) ⇒ netty default 2×P.
- Pipeline: PipelineConfigurator.initializeChannel (:146-167; channel class :151);
  configureInitialPipeline (:252-291); V5+ configureModernPipeline (:293-362) installs
  FRAME_DECODER/ENCODER + MESSAGE_PROCESSOR = CQLMessageHandler; legacy (:405-416).
- CQLMessageHandler.processOneContainedMessage (:180) / processRequest (:386-422) run ON the
  event loop; decode (:391) then dispatcher.dispatch (:392).
- **Handoff to NTR**: Dispatcher.dispatch (Dispatcher.java:108-133) wraps RequestProcessor,
  executor.submit at :131; requestExecutor = SHARED.newExecutor(native_transport_max_threads,
  "Native-Transport-Requests") (:62-65). Auth → authExecutor (:81-84, selected :125-129).
  Exception: processRequest may run on the loop only during protocol negotiation (:362-363,445-446).
- No `useLegacyNativeTransportBehavior` flag exists; only useNativeTransportLegacyFlusher
  (DatabaseDescriptor.java:3784; Server.java:114) → Flusher.legacy vs immediate
  (Dispatcher.java:488-502), both on the channel's event loop. Affects response flushing only.
- **SELECT coordinator work executes on the NTR thread**: QueryMessage.execute
  (QueryMessage.java:102-134; parse :117, process :118) → SelectStatement.execute →
  StorageProxy.read (StorageProxy.java:2173).

## 2. Internode messaging inbound

- InboundMessageHandler on internode event loop; small messages deserialized ON the loop
  (processSmallMessage :161-216); large on the target stage (ProcessLargeMessage.provideMessage
  :530-533).
- **Loop → Stage handoff**: dispatch(ProcessMessage) :420-430, specifically
  `header.verb.stage.execute(ExecutorLocals.create(state), task)` :429. Task run → InboundSink
  consumer (:460) → verb.handler().doVerb (InboundSink.java:88-105, dispatch :104; doc :50-52).
- Verb→Stage: Verb enum ctor list (Verb.java:201-408; stage field :430,475). Keys:
  MUTATION_REQ:202→MUTATION (MutationVerbHandler); MUTATION_RSP:201→REQUEST_RESPONSE;
  READ_REQ:228→READ (ReadCommandVerbHandler); READ_RSP:227→REQUEST_RESPONSE; RANGE :229-230;
  COUNTER :225; FAILURE_RSP:389. RESPONSE_HANDLER = ResponseVerbHandler (import :168).
- Outbound: OutboundConnection — any thread enqueues, ONE delivery consumer (event loop or
  companion) (OutboundConnection.java:93-105); enqueue :336 → delivery.execute :360; loop =
  socketFactory.defaultGroup().next() :321; EventLoopDelivery small/urgent,
  LargeMessageDelivery on synchronousWorkExecutor :327-329.
- Groups: acceptGroup 1, defaultGroup EVENT_THREADS, outboundStreamingGroup EVENT_THREADS
  (SocketFactory.java:195-197); EVENT_THREADS = INTERNODE_EVENT_THREADS default P (:90).
  Channel→loop assignment = Netty DefaultEventExecutorChooserFactory round-robin, NOT
  token-aware (:111,137). Event-loop queue = ManyToOneConcurrentLinkedQueue (:115,140).

## 3. Thread hops end-to-end

### QUORUM single-partition read — coordinator
1. Bytes on native event loop; CQLMessageHandler decode → Dispatcher.dispatch.
2. **HOP A** loop → NTR (Dispatcher.java:131).
3. NTR: QueryMessage.execute → StorageProxy.read (:2173) →
   dispatchReadWithRetryOnDifferentSystem (:2191) → AbstractReadExecutor.executeAsync (:181) →
   makeRequests (:138-170).
4. Remote: coordinator.sendReadCommand (:161) → sendWithCallback (ReadCoordinator.java:51-59);
   callbacks.addWithExpiration (MessagingService.java:430) → OutboundConnection.enqueue (:336).
   **HOP B**: serialization+write on outbound Messaging-EventLoop delivery thread.
5. Local replica (isSelf && localReadSupported, :149): deferred last, then
   **Stage.READ.maybeExecuteImmediately(LocalReadRunnable)** (AbstractReadExecutor.java:168) —
   inline on NTR if READ permit free, else enqueue (**HOP C conditional**). LocalReadRunnable →
   executeLocally + handler.response (StorageProxy.java:2740-2776).
6. **Coordinator BLOCKS**: awaitResponses → ReadCallback.awaitResults (:134-136) → await →
   condition.awaitUntil(deadline) (:104-132; condition = newOneTimeCondition() :71). NTR parks.

### Replica
7. Bytes on replica internode loop; small-message deserialize on loop (:161).
8. **HOP** loop → READ stage (:429; READ_REQ→READ Verb.java:228).
9. READ stage: InboundSink.accept (:120) → ReadCommandVerbHandler.doVerb (:68) → doRead
   (:56-66, executeLocally) → MessagingService.send(reply) (:129); write on replica outbound loop.

### Back on coordinator
10. Response on internode loop; **HOP** loop → REQUEST_RESPONSE (READ_RSP :227; :429).
11. ResponseVerbHandler.doVerb (:62; callbacks.remove :64; cb.onResponse :85) →
    ReadCallback.onResponse (:217) → resolver.preprocess + condition.signalAll (:242) —
    **wakes the parked NTR thread**.
12. NTR resumes, merges (StorageProxy.java:2709-2718); Dispatcher.processRequest → flush
    (:485,488-502). **HOP** NTR → native loop: Flusher on item.channel.eventLoop() (:490).

### QUORUM mutation
- Same entry; StorageProxy.mutate/performWrite → sendToHintedReplicas (~:1860-1932).
- Remote: sendWriteWithCallback (:1924; MessagingService.java:447-451).
- Local: performLocally (:1918) → **Stage.MUTATION.maybeExecuteImmediately(LocalMutationRunnable)**
  (:1995, general :2025); run :3180-3214 (deadline→hint conversion :3186-3204).
- **Coordinator BLOCKS**: AbstractWriteResponseHandler.get() → condition.await
  (:137; condition :83).
- Replica: loop → MUTATION stage; MutationVerbHandler.doVerb (:52) → applyMutation (:80-84):
  **payload.applyFuture().addCallback(respond, failed)** (:83) — CONTINUATION-STYLE, stage
  thread released; respond (:38-45) fires from the apply future (Keyspace/commitlog executor).
  applyFuture = Mutation.java:289-292.
- Coordinator: loop → REQUEST_RESPONSE → onResponse → signal → condition.signalAll
  (AbstractWriteResponseHandler.java:346-359) → NTR wakes → flush on native loop.

## 4. Async/future machinery present

- Model = callback delivery + blocking coordinator (hybrid). RequestCallback interface
  (net/RequestCallback.java:32-84); registered via RequestCallbacks
  (MessagingService.java:430,450); invoked from ResponseVerbHandler on REQUEST_RESPONSE
  (:77-86).
- Blocking points: ReadCallback.condition (:71,126,242,281); AbstractWriteResponseHandler
  (:83,137,359,449). **NTR thread held for full replica RTT — the main TPC target.**
- Continuation-style already on replica write (MutationVerbHandler.java:83).
- Infrastructure available: AsyncPromise (utils/concurrent/AsyncPromise.java:32), Future,
  AsyncOneResponse (net/AsyncOneResponse.java:28 — promise-based RequestCallback),
  ExecutorPlus.submit → Future (Stage.java:131-133), AsyncChannelPromise/
  AsyncChannelOutputPlus. Mainline coordinator does NOT chain them; it blocks on Condition.

## 5. Earliest token-known points (shard-routing hooks)

- CQL: token NOT known at arrival/decode. First computable during execution on NTR:
  restrictions.getPartitionKeys (SelectStatement.java:821) →
  table.partitioner.decorateKey (:839 single-key; :847 multi-key). Replica plan from
  command.partitionKey().getToken() (AbstractReadExecutor.java:206-213).
  ⇒ earliest CQL shard-routing = after parse+bind, inside getSliceCommands. The loop→NTR hop
  (Dispatcher.java:131) happens BEFORE the token is known.
- Internode: header (verb,id,epoch,expiry,from,params — extractHeader
  InboundMessageHandler.java:131) does NOT carry token. Token after payload deserialization:
  reads ReadCommandVerbHandler.checkTokenOwnership (:186; range :214), writes
  Mutation.key().getToken(). Small messages deserialize ON the loop (:171) ⇒ token knowable
  on-loop for small messages; large deserialize on stage.

## 6. Prior art — affinity/NUMA/per-core

- NONE in src/java (grep affinity|NUMA|isolcpus|CpuLayout etc = 0). lib/affinity-3.23.3.jar is
  transitive-only (Chronicle BOM); no net.openhft.affinity usage.
- Event-loop assignment round-robin (DefaultEventExecutorChooserFactory, SocketFactory.java:111,137).
- Inline-execution analogs: Stage.IMMEDIATE (ImmediateExecutor; all Accord verbs Verb.java:326-383,
  PAXOS2_CLEANUP_FINISH_PREPARE_REQ :296); ExecutorPlus.maybeExecuteImmediately
  (SEPExecutor.java:205-227; used AbstractReadExecutor.java:168, StorageProxy.java:1995,2025,933);
  Flusher.immediate vs legacy (Dispatcher.java:494).
- Useful primitives in-tree: ManyToOneConcurrentLinkedQueue on event loops
  (SocketFactory.java:115,140), jctools-core-3.1.0 bundled.

## Handoff points TPC must replace (summary)

1. native loop → NTR (Dispatcher.java:131)
2. NTR → outbound Messaging-EventLoop (OutboundConnection.java:336)
3. NTR → READ/MUTATION for local replica (AbstractReadExecutor.java:168, StorageProxy.java:1995) — sometimes already inlined
4. coordinator BLOCKS on Condition (ReadCallback.java:126, AbstractWriteResponseHandler.java:137)
5. replica loop → READ/MUTATION stage (InboundMessageHandler.java:429)
6. replica stage → outbound loop (MessagingService.send)
7. coordinator loop → REQUEST_RESPONSE → callback signal (:429 → ResponseVerbHandler.java:85)
8. NTR → native loop response flush (Dispatcher.java:490)
