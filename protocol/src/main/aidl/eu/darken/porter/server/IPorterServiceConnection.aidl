package eu.darken.porter.server;

interface IPorterServiceConnection {

    oneway void connected(IBinder service) = 1;

    oneway void died() = 2;
}
