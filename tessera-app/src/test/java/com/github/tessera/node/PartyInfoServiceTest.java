package com.github.tessera.node;

import com.github.tessera.config.Config;
import com.github.tessera.config.Peer;
import com.github.tessera.config.ServerConfig;
import com.github.tessera.key.KeyManager;
import com.github.tessera.key.exception.KeyNotFoundException;
import com.github.tessera.nacl.Key;
import com.github.tessera.node.model.Party;
import com.github.tessera.node.model.PartyInfo;
import com.github.tessera.node.model.Recipient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.*;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

public class PartyInfoServiceTest {

    private static final String URI = "http://localhost:8080";

    private static final Set<Recipient> NEW_RECIPIENTS = Stream
        .of(
            new Recipient(new Key("url1".getBytes()), "url1"),
            new Recipient(new Key("url2".getBytes()), "url2")
        ).collect(toSet());

    private PartyInfoStore partyInfoStore;

    private Config configuration;

    private KeyManager keyManager;

    private PartyInfoService partyInfoService;

    @Before
    public void onSetUp() {

        this.partyInfoStore = mock(PartyInfoStore.class);
        this.configuration = mock(Config.class);
        this.keyManager = mock(KeyManager.class);

        final ServerConfig serverConfig = new ServerConfig("http://localhost", 8080, null);
        doReturn(serverConfig).when(configuration).getServerConfig();

        final Peer peer = new Peer("http://other-node.com:8080");
        doReturn(singletonList(peer)).when(configuration).getPeers();

        final Set<Key> ourKeys = new HashSet<>(
            Arrays.asList(
                new Key("some-key".getBytes()),
                new Key("another-public-key".getBytes())
            )
        );
        doReturn(ourKeys).when(keyManager).getPublicKeys();
    }

    @After
    public void after() {
        verifyNoMoreInteractions(partyInfoStore);
    }

    @Test
    public void initialPartiesCorrectlyReadFromConfiguration() {
        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final PartyInfo partyInfo = new PartyInfo(URI, emptySet(), singleton(new Party("http://other-node.com:8080")));
        doReturn(partyInfo).when(partyInfoStore).getPartyInfo();

        final Set<Party> initialParties = partyInfoService.getPartyInfo().getParties();
        final Set<Recipient> initialRecipients = partyInfoService.getPartyInfo().getRecipients();
        final String ourUrl = partyInfoService.getPartyInfo().getUrl();

        assertThat(initialParties).hasSize(1).containsExactly(new Party("http://other-node.com:8080"));
        assertThat(initialRecipients).hasSize(0);
        assertThat(ourUrl).isEqualTo(URI);

        verify(partyInfoStore).store(any(PartyInfo.class));
        verify(partyInfoStore, times(3)).getPartyInfo();

        //TODO: add a captor for verification
    }

    @Test
    public void registeringPublicKeysUsesOurUrl() {

        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final ArgumentCaptor<PartyInfo> captor = ArgumentCaptor.forClass(PartyInfo.class);

        verify(partyInfoStore).store(captor.capture());

        final List<Recipient> allRegisteredKeys = captor
            .getAllValues()
            .stream()
            .map(PartyInfo::getRecipients)
            .flatMap(Set::stream)
            .collect(toList());

        assertThat(allRegisteredKeys)
            .hasSize(2)
            .containsExactlyInAnyOrder(
                new Recipient(new Key("some-key".getBytes()), URI),
                new Recipient(new Key("another-public-key".getBytes()), URI)
            );
    }

    @Test
    public void updatePartyInfoDelegatesToStore() {

        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final String secondParty = "http://other-node.com:8080";
        final String thirdParty = "http://third-url.com:8080";

        final PartyInfo secondNodePartyInfo = new PartyInfo(secondParty, emptySet(), emptySet());
        final PartyInfo thirdNodePartyInfo = new PartyInfo(thirdParty, emptySet(), emptySet());

        partyInfoService.updatePartyInfo(secondNodePartyInfo);
        partyInfoService.updatePartyInfo(thirdNodePartyInfo);

        verify(partyInfoStore).store(secondNodePartyInfo);
        verify(partyInfoStore).store(thirdNodePartyInfo);
        verify(partyInfoStore, times(3)).store(any(PartyInfo.class));
        verify(partyInfoStore, times(2)).getPartyInfo();
    }

    @Test
    public void getRecipientURLFromPartyInfoStore() {

        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final Recipient recipient = new Recipient(new Key("key".getBytes()), "someurl");
        final PartyInfo partyInfo = new PartyInfo(URI, singleton(recipient), emptySet());
        doReturn(partyInfo).when(partyInfoStore).getPartyInfo();

        final String result = partyInfoService.getURLFromRecipientKey(new Key("key".getBytes()));
        assertThat(result).isEqualTo("someurl");

        verify(partyInfoStore).store(any(PartyInfo.class));
        verify(partyInfoStore).getPartyInfo();
    }

    @Test
    public void getRecipientURLFromPartyInfoStoreFailsIfKeyDoesntExist() {

        doReturn(new PartyInfo("", emptySet(), emptySet())).when(partyInfoStore).getPartyInfo();

        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final Key failingKey = new Key("otherKey".getBytes());
        final Throwable throwable = catchThrowable(() -> partyInfoService.getURLFromRecipientKey(failingKey));
        assertThat(throwable).isInstanceOf(KeyNotFoundException.class).hasMessage("Recipient not found");

        verify(partyInfoStore).store(any(PartyInfo.class));
        verify(partyInfoStore).getPartyInfo();
    }

    @Test
    public void diffPartyInfoReturnsFullSetOnEmptyStore() {
        doReturn(new PartyInfo("", emptySet(), emptySet())).when(partyInfoStore).getPartyInfo();

        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final PartyInfo incomingInfo = new PartyInfo("", NEW_RECIPIENTS, emptySet());

        final Set<Recipient> unsavedRecipients = this.partyInfoService.findUnsavedRecipients(incomingInfo);

        assertThat(unsavedRecipients)
            .hasSize(2)
            .containsExactlyInAnyOrder(NEW_RECIPIENTS.toArray(new Recipient[0]));

        verify(partyInfoStore).store(any(PartyInfo.class));
        verify(partyInfoStore).getPartyInfo();
    }

    @Test
    public void diffPartyInfoReturnsEmptySetOnFullStore() {
        doReturn(new PartyInfo("", NEW_RECIPIENTS, emptySet())).when(partyInfoStore).getPartyInfo();

        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final PartyInfo incomingInfo = new PartyInfo("", NEW_RECIPIENTS, emptySet());

        final Set<Recipient> unsavedRecipients = this.partyInfoService.findUnsavedRecipients(incomingInfo);

        assertThat(unsavedRecipients).isEmpty();

        verify(partyInfoStore).store(any(PartyInfo.class));
        verify(partyInfoStore).getPartyInfo();
    }

    @Test
    public void diffPartyInfoReturnsNodesNotInStore() {
        doReturn(
            new PartyInfo(
                "",
                singleton(new Recipient(new Key("url1".getBytes()), "url1")),
                emptySet()
            )
        ).when(partyInfoStore).getPartyInfo();

        this.partyInfoService = new PartyInfoServiceImpl(partyInfoStore, configuration, keyManager);

        final PartyInfo incomingInfo = new PartyInfo("", NEW_RECIPIENTS, emptySet());

        final Set<Recipient> unsavedRecipients = this.partyInfoService.findUnsavedRecipients(incomingInfo);

        assertThat(unsavedRecipients)
            .hasSize(1)
            .containsExactlyInAnyOrder(new Recipient(new Key("url2".getBytes()), "url2"));

        verify(partyInfoStore).store(any(PartyInfo.class));
        verify(partyInfoStore).getPartyInfo();
    }

}